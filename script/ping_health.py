import time
import urllib.request
import urllib.error
from datetime import datetime

URL = "https://secapi-ibir.onrender.com/actuator/health"
INTERVAL_SECONDS = 60

def ping_health():
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{timestamp}] Pinging {URL}...")
    try:
        req = urllib.request.Request(
            URL, 
            headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) HealthCheck/1.0'}
        )
        # Set a timeout of 10 seconds to avoid hanging
        with urllib.request.urlopen(req, timeout=10) as response:
            status_code = response.getcode()
            body = response.read().decode('utf-8')
            print(f"[{timestamp}] Success! Status: {status_code} | Response: {body}")
    except urllib.error.HTTPError as e:
        # Actuator/health might return non-200 status (e.g. 503 if DOWN)
        try:
            body = e.read().decode('utf-8')
        except Exception:
            body = "Could not read error response body"
        print(f"[{timestamp}] HTTP Error! Status: {e.code} | Response: {body}")
    except urllib.error.URLError as e:
        print(f"[{timestamp}] URL Error! Reason: {e.reason}")
    except Exception as e:
        print(f"[{timestamp}] Unexpected error: {e}")

def main():
    print("Starting Health Check Ping Service...")
    print(f"Target URL: {URL}")
    print(f"Interval: {INTERVAL_SECONDS} seconds")
    print("Press Ctrl+C to stop.")
    print("-" * 50)
    
    # Run the first ping immediately
    ping_health()
    
    try:
        while True:
            time.sleep(INTERVAL_SECONDS)
            ping_health()
    except KeyboardInterrupt:
        print("\nStopping Health Check Ping Service. Goodbye!")

if __name__ == "__main__":
    main()
