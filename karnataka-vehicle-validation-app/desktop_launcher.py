import threading
import time
import webbrowser

from app import HOST, PORT, create_server


def main() -> None:
    server = create_server(HOST, PORT)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    time.sleep(1)
    webbrowser.open(f'http://{HOST}:{PORT}')
    print(f'KA-Vehicle-PUC-Check is running at http://{HOST}:{PORT}')
    print('Close this window to stop the app.')

    try:
        while thread.is_alive():
            time.sleep(0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()


if __name__ == '__main__':
    main()

