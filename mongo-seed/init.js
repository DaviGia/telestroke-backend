db.createUser({
    user: 'demo',
    pwd: 'demo',
    roles: [
        {
            role: 'readWrite',
            db: 'stroke-data',
        }
    ]
});