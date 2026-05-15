const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder');
const pvp = require('mineflayer-pvp').plugin;
const armorManager = require('mineflayer-armor-manager');
const mcDataFactory = require('minecraft-data');

const args = process.argv.slice(2);
const [host, port, username, desc, version] = args;

console.log(`\x1b[36m[Core] 初始化连接: ${host}:${port} | 协议: ${version}\x1b[0m`);

const bot = mineflayer.createBot({
    host: host,
    port: parseInt(port),
    username: username,
    version: version, // 1.21.11
    auth: 'offline',
    checkTimeoutInterval: 60000
});

bot.loadPlugin(pathfinder);
bot.loadPlugin(pvp);
bot.loadPlugin(armorManager);

bot.on('spawn', () => {
    console.log(`\x1b[32m[Core] 节点已就绪: ${desc} (${username})\x1b[0m`);
    const mcData = mcDataFactory(bot.version);
    
    // 定时任务逻辑：模拟随机游走，保持活跃
    setInterval(() => {
        if (bot.entity && bot.entity.position) {
            const rx = (Math.random() - 0.5) * 10;
            const rz = (Math.random() - 0.5) * 10;
            const dest = bot.entity.position.offset(rx, 0, rz);
            
            const movements = new Movements(bot, mcData);
            bot.pathfinder.setMovements(movements);
            bot.pathfinder.setGoal(new goals.GoalNear(dest.x, dest.y, dest.z, 1));
            console.log(`\x1b[36m[Task] ${username} 执行位移同步: ${dest.x.toFixed(0)}, ${dest.z.toFixed(0)}\x1b[0m`);
        }
    }, 5000);
});

bot.on('kicked', (reason) => console.log(`\x1b[31m[Warn] 节点被踢出: ${reason}\x1b[0m`));
bot.on('error', (err) => console.error(`\x1b[31m[Error] 节点错误: ${err.message}\x1b[0m`));
bot.on('end', () => process.exit(1));