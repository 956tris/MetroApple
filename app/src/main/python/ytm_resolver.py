import yt_dlp
import json
import threading

# Order of clients to try based on reliability/SABR status
_DEFAULT_CLIENTS = ['web_remix', 'ios', 'android_vr', 'tvhtml5_simply', 'visionos', 'tvhtml5', 'web_creator']

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


def _build_opts(client, player_po_token=None, streaming_po_token=None, visitor_data=None, cookie=None):
    opts = {
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

    # Forward the user's YouTube session cookie (same one used elsewhere for
    # Innertube header auth) as a raw Cookie header. yt-dlp applies
    # http_headers to every request it makes for this extraction, including
    # the innertube player/next calls - this is what lets clients that
    # require sign-in (age-gated content, some 'web_creator'-style requests)
    # succeed instead of failing with "Please sign in".
    if cookie:
        opts['http_headers'] = {'Cookie': cookie}

    # PO tokens generated via PoTokenGenerator (WebView botguard). Format
    # confirmed against yt-dlp's current PO-Token-Guide: comma-separated
    # "CLIENT.CONTEXT+TOKEN" entries, context is "gvs" (googlevideo/
    # streaming URLs) or "player" (innertube player request). GVS tokens
    # are rejected without visitor_data alongside them, so that's threaded
    # through too whenever we have a token to send.
    po_tokens = []
    if streaming_po_token:
        po_tokens.append(f'{client}.gvs+{streaming_po_token}')
    if player_po_token:
        po_tokens.append(f'{client}.player+{player_po_token}')
    if po_tokens:
        opts['extractor_args']['youtube']['po_token'] = po_tokens
        if visitor_data:
            opts['extractor_args']['youtube']['visitor_data'] = visitor_data

    return opts


def _try_client(video_id, client, player_po_token=None, streaming_po_token=None, visitor_data=None, cookie=None):
    opts = _build_opts(client, player_po_token, streaming_po_token, visitor_data, cookie)
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(f'https://www.youtube.com/watch?v={video_id}', download=False)
        if info and 'url' in info:
            return info
    return None


def resolve(video_id, player_po_token=None, streaming_po_token=None, visitor_data=None, cookie=None):
    global _last_working_client

    # Try last-known-good client first so the common case is a single
    # attempt instead of a walk through the whole list.
    with _lock:
        preferred = _last_working_client

    ordered_clients = list(_DEFAULT_CLIENTS)
    if preferred and preferred in ordered_clients:
        ordered_clients.remove(preferred)
        ordered_clients.insert(0, preferred)

    # 'web_creator' hard-requires a signed-in session - without a cookie it
    # always fails with "Please sign in" and just wastes an attempt (and, if
    # it's the last client tried, surfaces that confusing error to the user
    # even though other clients might have worked fine).
    if not cookie:
        ordered_clients = [c for c in ordered_clients if c != 'web_creator']

    last_error = None

    for client in ordered_clients:
        try:
            info = _try_client(video_id, client, player_po_token, streaming_po_token, visitor_data, cookie)
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
