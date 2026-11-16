#!/system/bin/sh
# Stock Yandex spotter bridge for the second wake word "Яндекс".
# Mirrors run_spotter.sh but uses the dedicated yandex model and its own FIFO.
FIFO=/data/local/spotter-root/run/spotter-yandex.fifo
LOG=/data/local/spotter-root/spotter-yandex.log
PIDFILE=/data/local/spotter-root/run/spotter-yandex.pid
LAST=/data/local/spotter-root/run/spotter-yandex.last_hit
mkdir -p /data/local/spotter-root/run
if [ -f "$PIDFILE" ]; then
  pid=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    exit 0
  fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"; pkill -P $$ 2>/dev/null' 0 1 2 15
pkill -f '^/lib/ld-linux-aarch64.so.1 --library-path /lib /probe /models/yandex-2022 pcm$' 2>/dev/null
[ -p "$FIFO" ] || mkfifo "$FIFO"
echo "$(date) wrapper_start" >> "$LOG"
while true; do
  cat "$FIFO" | chroot /data/local/spotter-root /lib/ld-linux-aarch64.so.1 --library-path /lib /probe /models/yandex-2022 pcm 2>>"$LOG" | while read line; do
    case "$line" in
      *detected_phrase_id=1*)
        now=$(date +%s)
        last=$(cat "$LAST" 2>/dev/null || echo 0)
        if [ $((now - last)) -lt 3 ]; then continue; fi
        echo "$now" > "$LAST"
        echo "$(date) HIT $line" >> "$LOG"
        BODY='{"wake":true,"keyword":"yandex"}'
        printf 'POST /api/assistant/trigger HTTP/1.0\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n%s' "${#BODY}" "$BODY" | toybox nc -w 2 127.0.0.1 8080 >/dev/null 2>&1
        echo "$(date) wake_post" >> "$LOG"
        sleep 2
        ;;
    esac
  done
  echo "$(date) probe_exit" >> "$LOG"
  sleep 1
done
