import yt_dlp
import json


def get_version():
    """Returns the currently installed yt-dlp version string."""
    return yt_dlp.version.__version__


def resolve(video_id):
    # Order of clients to try based on reliability/SABR status
    clients = ['web_remix', 'ios', 'android_vr', 'tvhtml5_simply']

    # Base options for yt-dlp
    base_ydl_opts = {
        'format': 'bestaudio/best',
        'quiet': True,
        'no_warnings': True,
        # 'extract_flat': True, # We need the real URL
    }

    last_error = None

    for client in clients:
        ydl_opts = base_ydl_opts.copy()
        # yt-dlp allows specifying clients via extractor-args
        # Example: --extractor-args "youtube:player-client=web_remix"
        ydl_opts['extractor_args'] = {
            'youtube': {
                'player_client': [client]
            }
        }

        try:
            with yt_dlp.YoutubeDL(ydl_opts) as ydl:
                info = ydl.extract_info(f'https://www.youtube.com/watch?v={video_id}', download=False)

                # Check if we got a direct URL
                if 'url' in info:
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
            continue

    return json.dumps({'error': last_error or 'All clients failed'})
