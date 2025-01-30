# MindustryServerManager

Server manager:

- Private map, plugin (using bearer token)
- Auto translate chat
- Server access token, remote control, public id
- Server cluster image (docker)
- Mindustry server custom image (docker)
- Server cluster:

  - Server cluster hub
  - Admin managment, ban user, ip ban, user info
  - File managment
  - Custom notification (email, discord, webhook)
  - Chat to discord, discord to chat (webhook)
  - Custom command, command auto complete

- Server auto backup (file)
- Anti grief, nsfw
- Transfer server

## Setup server

- Install docker
- Pull mindustry server image: `docker pull ghcr.io/mindustryvn/mindustry-server-image:latest`
- Run server manager: `docker compose up`
- Go to mindustry-tool.com, create a new server manager, get SECURITY_KEY, ACCESS_TOKEN
- Update docker-compose.yml with SECURITY_KEY, ACCESS_TOKEN (you should keep it secret, you can use .env or edit vps env)
- Rerun server manager: ` docker compose down`  `docker compose ip`

