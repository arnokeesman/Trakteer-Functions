const express = require('express');
const app = express();
const fs = require('fs');
const port = 6883;
const dataFile = "./supports.json"
const users = require('./users.json');
const units = require('./units.json');
const bodyParser = require('body-parser');
app.use(bodyParser.urlencoded({ extended: true }));

app.set('views', './views');
app.set('view engine', 'ejs');

app.use(express.static('public'));

app.get('/', (req, res) => {
    res.render('home', { users });
});

app.get('/donate/:name', (req, res) => {
    const user = users.find(user => user.name === req.params.name);
    if (!user) return res.sendStatus(404);
    res.render('donate', { user, units });
});

app.post('/donate/:name', (req, res) => {
    try {
        const user_name = req.params.name;
        const user = users.find(user => user.name === user_name);
        if (!user) return res.sendStatus(404);

        const selectedName = req.body.supporterNameSelect;
        const supporterName = selectedName === 'custom' ? req.body.supporterName : selectedName;
        const supportMessage = req.body.supportMessage || null;
        const quantity = parseInt(req.body.quantity);
        const unit_id = req.body.unitID;
        const unit = units.find(unit => unit.id == unit_id);

        if (!supporterName || !quantity || !unit) throw new Error();

        const amount = unit.price * quantity;

        const supports = require(dataFile);
        supports.unshift({
            user_id: user.id,
            supporter_name: supporterName,
            support_message: supportMessage,
            quantity,
            amount,
            unit_name: unit.name,
            updated_at: new Date().toISOString()
                .replace(/T/, ' ')
                .replace(/\..+/, '')
        });
        writeJSON(dataFile, supports);

        res.render('thanks', { user, supporterName, supportMessage, quantity, unitName: unit.name, amount });
    } catch (err) {
        res.sendStatus(400);
    }
});

app.get('/users', (req, res) => {
    res.json({
        status: 'success',
        result: {
            data: users.map(user => ({ id: user.id, name: user.name }))
        }
    })
});

app.get('/supports', (req, res) => {
    const apiKey = req.headers['key'];
    if (!apiKey) return res.sendStatus(401);
    const user = users.find(user => user.apiKey === apiKey);
    if (!user) return res.sendStatus(403);
    const supports = require(dataFile);
    res.json({
        status: 'success',
        result: {
            data: supports.filter(support => support.user_id === user.id)
        }
    })
});

app.listen(port, () => {
    console.log(`Fake Trakteer API listning on http://localhost:${port}`)
});

function writeJSON(filename, data) {
    fs.writeFileSync(filename, JSON.stringify(data, null, 4));
}

if (!fs.existsSync(dataFile)) {
    fs.writeFileSync(dataFile, "[]");
}