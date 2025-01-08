FROM peerjs/peerjs-server:1.0.2

EXPOSE 9000

ENTRYPOINT ["node", "peerjs.js"]

# to enable https add: "--sslkey", "./certificates/key.pem", "--sslcert", "./certificates/cert.pem",
CMD [ "--port", "9000", "--path", "/telestroke", "--key", "demo", "--allow_discovery" ]