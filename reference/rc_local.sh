#!/system/bin/sh

BOARD=$(busybox awk -F= '/ro\.product\.model/{print $NF}' /system/build.prop)
echo "BOARD=$BOARD"

currentTime=`date +%s`
controlTime=`getprop ro.build.date.utc`

if [[ $currentTime -le $controlTime ]]; then
   busybox date +%s -s @$controlTime
else
   echo "Time was not changed"
fi

# Disable Captive Portal
settings put global captive_portal_server 127.0.0.1 &
settings put global captive_portal_detection_enable 0 &

# Start procedure to pause SCP task and related operations
setprop vendor.load_i2c_modules 1
# Wait until all operations will finished or continue after timeout
COUNTER=0
TIMEOUT=5
while [ $COUNTER -lt $TIMEOUT ]; do
   I2C_MODULES_READY=`getprop vendor.i2c_modules_ready | tr -d '\r' `
   if [ $I2C_MODULES_READY -eq 1 ]; then
      break
   fi
   COUNTER=$(($COUNTER+1))
   sleep 1
done

# Create symlinks for amplifiers settings
/vendor/bin/amplifiers_fw.sh

# Remove OTA zip from station fw (was created with root)
rm /cache/update*.zip


# Allow full access to data/quasar so that iosdk can copy the files and clear the directory (the access is still restricted in SELinux)
chmod -R a+rwx /data/quasar

# This script should live at least as long as all of it's child is alive.
# Overwise init process will not try to terminate child processes on system reboot.
while true; do sleep 65535; done  # sleep 18h
exit 0
