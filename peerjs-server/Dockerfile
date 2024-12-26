FROM peerjs/peerjs-server:0.6.1

EXPOSE 9000

ENTRYPOINT ["node", "bin/peerjs"]

# to enable https add: "--sslkey", "./certificates/key.pem", "--sslcert", "./certificates/cert.pem",
CMD [ "--port", "9000", "--path", "/telestroke", "--key", "demo", "--allow_discovery" ]