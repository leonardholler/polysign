#!/bin/bash
# Run on a fresh Amazon Linux 2023 EC2 instance
set -euo pipefail

sudo yum update -y
sudo yum install -y docker git java-25-amazon-corretto-devel
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER

# Docker Compose plugin
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

echo ""
echo "=== Setup complete ==="
echo "Log out and back in for docker group, then run: bash deploy/run.sh"
