import yt_dlp
import json
import threading

# Order of clients to try based on reliability/SABR status
_DEFAULT_CLIENTS = ['web_remix', 'ios', 'android_vr', 'tvhtml5_simply']

# Remember whichever client actually worked last, per process. Most calls
# then need exactly one attempt instead of walking the full list from
# scratch every time - the previous version always started at web_remix
# even when e.g. ios was the one that had been working for this device/
# network all along.
_last_working_client = None
_lock = threading.Lock()


def get_version():
    """Returns the currently installed yt-dlp version string."""
    return yt_dlp.version.__version__


def _build_opts(client):
    return {
        # Narrowed selector: avoids yt-dlp building/sorting the full
        # (audio+video) format list before picking - we only ever want
        # audio.
        'format': 'bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio',
        'quiet': True,
        'no_warnings': True,
        # We only need one direct progressive/adaptive audio URL - HLS and
        # DASH manifests would otherwise be fetched and parsed too even
        # though we never use them (no live/adaptive-segment playback
        # here), and that's usually the single biggest latency cost in a
        # "just resolve me a URL" extraction. translated_subs is also
        # irrelevant for audio-only resolution.
        'extractor_args': {
            'youtube': {
                'player_client': [client],
                'skip': ['hls', 'dash', 'translated_subs'],
            }
        },
        # Bound how long a hung/slow client can block failover.
        'socket_timeout': 8,
    }


def _try_client(video_id, client):
    with yt_dlp.YoutubeDL(_build_opts(client)) as ydl:
        info = ydl.extract_info(f'https://www.youtube.com/watch?v={video_id}', download=False)
        if info and 'url' in info:
            return info
    return None


def resolve(video_id):
    global _last_working_client

    # Try last-known-good client first so the common case is a single
    # attempt instead of a walk through the whole list.
    with _lock:
        preferred = _last_working_client

    ordered_clients = list(_DEFAULT_CLIENTS)
    if preferred and preferred in ordered_clients:
        ordered_clients.remove(preferred)
        ordered_clients.insert(0, preferred)

    last_error = None

    for client in ordered_clients:
        try:
            info = _try_client(video_id, client)
            if info:
                with _lock:
                    _last_working_client = client
                return json.dumps({
                    'url': info['url'],
                    'working_client': client,
                    'itag': info.get('format_id'),
                    'mime': info.get('ext'),
                    'bitrate': info.get('abr'),
                    'acodec': info.get('acodec'),
                    'asr': info.get('asr'),
                    'filesize': info.get('filesize') or info.get('filesize_approx'),
                    'ytdlp_version': yt_dlp.version.__version__,
                })
        except Exception as e:
            last_error = str(e)
            # This client failed - if it was our cached "last working"
            # client, clear it so the next call doesn't retry a client
            # that's stopped working (e.g. YouTube changed something).
            with _lock:
                if _last_working_client == client:
                    _last_working_client = None
            continue

    return json.dumps({'error': last_error or 'All clients failed'})
