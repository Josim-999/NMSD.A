// PM2: pm2 start deploy/ecosystem.config.js && pm2 save && pm2 startup
// Two processes so a slow platform API never blocks Telegram approvals.
module.exports = {
  apps: [
    {
      name: "smt-agent",
      script: "python3",
      args: "-m smt run",
      cwd: "/opt/smt",
      interpreter: "none",
      autorestart: true,
      restart_delay: 10000,
      max_restarts: 50,
      env: { PYTHONUNBUFFERED: "1", TZ: "Europe/London" },
    },
    {
      name: "smt-bot",
      script: "python3",
      args: "-m smt bot",
      cwd: "/opt/smt",
      interpreter: "none",
      autorestart: true,
      restart_delay: 5000,
      env: { PYTHONUNBUFFERED: "1", TZ: "Europe/London" },
    },
  ],
};
