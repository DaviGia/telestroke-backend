FROM mongo

# import base data
COPY ./backup/*.json ./

CMD mongoimport --host mongo --username demo --password demo --db stroke-data --collection user --type json --file user.json ; mongoimport --host mongo --username demo --password demo --db stroke-data --collection user-details --type json --file user-details.json ; mongoimport --host mongo --username demo --password demo --db stroke-data --collection checklist --type json --file checklist.json ; mongoimport --host mongo --username demo --password demo --db stroke-data --collection template --type json --file template.json