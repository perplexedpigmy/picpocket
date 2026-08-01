#!/bin/bash
set -e

# rclone mount is handled on the host side and bind-mounted into the container
# Start Nextcloud via the saved official entrypoint
exec /usr/local/bin/docker-entrypoint.sh apache2-foreground