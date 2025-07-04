package icu.sakina.reBuilds

import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.util.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ReBuilds : JavaPlugin(), Listener {
    // 配置常量
    private val spawnRadius = 100000.0 // 半径1000格
    private val maxAttempts = 300 // 最大尝试次数
    private lateinit var center: Location
    private val safeSpawns = mutableListOf<Location>() // 安全出生点池
    private var scannedChunks = 0 // 已扫描的区块计数
    private var safeSpawnCount = 0 // 可以作为出生点的区块计数
    private val scanLimit = 200 // 扫描上限

    // 任务调度相关
    private var searchTaskId = -1
    private var statusTaskId = -1
    private var currentAttempt = 0
    private var dynamicDelay = 1L // 动态调整的延迟时间

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        logger.info("RandomSpawnPlugin Started!")

        try {
            center = Location(server.worlds.firstOrNull { it.environment == World.Environment.NORMAL } ?: return, 0.0, 0.0, 0.0)
        } catch (e: Exception) {
            handleException(e, "Went wrong when prepare 'Middle'")
        }

        // 开始慢速搜索安全出生点
        startSlowSearchForSafeSpawns()

        // 每10秒输出一次日志
        statusTaskId = server.scheduler.scheduleSyncRepeatingTask(this, Runnable {
            logStatus()
        }, 0L, 30 * 20L) // 10秒延迟，每10秒执行一次
    }

    override fun onDisable() {
        // 取消所有正在运行的任务
        if (searchTaskId != -1) server.scheduler.cancelTask(searchTaskId)
        if (statusTaskId != -1) server.scheduler.cancelTask(statusTaskId)
    }

    private fun startSlowSearchForSafeSpawns() {
        // 使用分步加载策略，每次只处理一个区块
        searchTaskId = server.scheduler.scheduleSyncRepeatingTask(this, Runnable {
            if (scannedChunks >= scanLimit) {
                logger.info("Scan limit reached. Stopping search task.")
                server.scheduler.cancelTask(searchTaskId)
                return@Runnable
            }

            if (currentAttempt >= maxAttempts) {
                logger.info("Max attempts reached for this tick. Resetting.")
                currentAttempt = 0
                return@Runnable
            }

            findAndStoreSafeSpawn()
            currentAttempt++
        }, 0L, dynamicDelay) // 动态调整的延迟时间
    }

    private fun findAndStoreSafeSpawn() {
        try {
            val (x, z) = generateRandomPoint()
            val world = getWorld() ?: run {
                logger.warning("World not found, skipping this attempt.")
                return
            }

            // 检查区块是否已加载
            val chunk = if (world.isChunkLoaded(x, z)) {
                world.getChunkAt(x, z)
            } else {
                // 如果TPS过低，减少加载频率
                if (server.tps[0] < 18.0) {
                    dynamicDelay = (dynamicDelay * 1.2).toLong().coerceAtMost(10L)
                }

                // 加载区块但不生成区块（减少负载）
                world.getChunkAt(x, z, false) ?: run {
                    logger.warning("Failed to load chunk at $x, $z")
                    return
                }
            }

            // 获取最高点（阻塞操作，但每次只做一次）
            val y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
            val location = Location(world, x.toDouble(), y.toDouble(), z.toDouble())

            if (isLocationSafe(location)) {
                val adjustedLocation = location.add(0.5, 0.0, 0.5)
                safeSpawns.add(adjustedLocation)
                safeSpawnCount++
                logger.info("Found safe spawn at $adjustedLocation")
            } else {
                // 如果不是安全出生点，且没有玩家在附近，卸载区块
                if (!isChunkNearPlayers(chunk)) {
                    chunk.unload(true)
                }
            }

            scannedChunks++

            // 如果TPS正常，尝试加快扫描速度
            if (server.tps[0] > 19.5 && dynamicDelay > 1L) {
                dynamicDelay = (dynamicDelay * 0.9).toLong().coerceAtLeast(1L)
            }
        } catch (e: Exception) {
            handleException(e, "Error occurred during safe spawn search")
        }
    }

    private fun isChunkNearPlayers(chunk: org.bukkit.Chunk): Boolean {
        val world = chunk.world
        val chunkX = chunk.x
        val chunkZ = chunk.z

        // 检查玩家是否在附近（±2区块范围内）
        return world.players.any { player ->
            val playerChunkX = player.location.blockX shr 4
            val playerChunkZ = player.location.blockZ shr 4
            Math.abs(playerChunkX - chunkX) <= 2 && Math.abs(playerChunkZ - chunkZ) <= 2
        }
    }

    private fun generateRandomPoint(): Pair<Int, Int> {
        return generateRandomPointInCircle()
    }

    private fun getWorld(): World? {
        return server.getWorld(center.world?.name ?: return null)
    }

    @EventHandler
    fun onPlayerFirstJoin(event: PlayerJoinEvent) {
        val player = event.player
        // 只处理首次加入的玩家
        if (!player.hasPlayedBefore()) {
            val overworld = server.worlds.firstOrNull { it.environment == World.Environment.NORMAL }
                ?: return // 找不到主世界时退出

            // 在主线程中执行安全位置选择
            server.scheduler.runTask(this, Runnable {
                try {
                    val spawnLocation = if (safeSpawns.isNotEmpty()) {
                        safeSpawns.random()
                    } else {
                        // 尝试快速找到一个安全出生点
                        findQuickSpawn(player) ?: overworld.spawnLocation
                    }

                    // 设置玩家出生点并传送
                    player.setRespawnLocation(spawnLocation, true)
                    player.sendMessage("${NamedTextColor.YELLOW}你的出生点已设置")
                    player.teleport(spawnLocation)
                    player.sendMessage(
                        "${NamedTextColor.GREEN}你的出生点已设置为: " +
                                "${spawnLocation.blockX}, ${spawnLocation.blockY}, ${spawnLocation.blockZ}"
                    )
                } catch (e: Exception) {
                    handleException(e, "Error occurred while handling first join event for player")
                    // 使用默认出生点作为后备方案
                    overworld.spawnLocation?.let {
                        player.teleport(it)
                        player.sendMessage("${NamedTextColor.RED}设置出生点时出错，使用默认出生点")
                    }
                }
            })
        }
    }

    private fun findQuickSpawn(player: Player): Location? {
        // 尝试在玩家周围快速寻找安全出生点
        val world = player.world
        val centerX = player.location.blockX
        val centerZ = player.location.blockZ

        // 在周围100x100范围内搜索
        (0 until 10).forEach { i ->
            val x = centerX + Random.nextInt(-50, 50)
            val z = centerZ + Random.nextInt(-50, 50)

            if (world.isChunkLoaded(x shr 4, z shr 4)) {
                val y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES)
                val location = Location(world, x.toDouble() + 0.5, y.toDouble(), z.toDouble() + 0.5)

                if (isLocationSafe(location)) {
                    return location
                }
            }
        }
        return null
    }

    private fun generateRandomPointInCircle(): Pair<Int, Int> {
        // 使用平方根分布确保均匀分布
        val radius = sqrt(Random.nextDouble()) * spawnRadius
        val angle = Random.nextDouble() * 2 * PI
        // 转换为整数坐标
        val x = (radius * cos(angle)).roundToInt()
        val z = (radius * sin(angle)).roundToInt()
        return Pair(x, z)
    }

    private fun isLocationSafe(loc: Location): Boolean {
        val world = loc.world ?: return false
        val blockX = loc.blockX
        val blockZ = loc.blockZ

        // 获取最高方块的高度
        val highestBlockY = world.getHighestBlockYAt(blockX, blockZ, HeightMap.WORLD_SURFACE)

        // 检查最高点是否在水上
        val surfaceBlock = world.getBlockAt(blockX, highestBlockY, blockZ)
        if (surfaceBlock.type == Material.WATER) {
            return false
        }

        // 检查从最高点向下到地面的方块
        for (y in highestBlockY downTo highestBlockY - 10) {
            if (y < 30) break

            val block = world.getBlockAt(blockX, y, blockZ)
            val material = block.type

            // 如果是空气或树叶，继续向下检查
            if (material == Material.AIR || isLeaves(material)) {
                continue
            }

            // 如果是水或岩浆，返回 false
            if (material == Material.WATER || material == Material.LAVA) {
                return false
            }

            // 检查危险方块
            if (isDangerousBlock(material)) {
                return false
            }

            // 检查站立点是否安全
            val standingBlock = world.getBlockAt(blockX, y + 1, blockZ)
            val headBlock = world.getBlockAt(blockX, y + 2, blockZ)

            if (standingBlock.type == Material.AIR && headBlock.type == Material.AIR) {
                return true
            }
        }

        // 如果没有找到合适的方块，返回 false
        return false
    }

    private fun isDangerousBlock(material: Material): Boolean {
        return when (material) {
            Material.CACTUS,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.LAVA,
            Material.MAGMA_BLOCK,
            Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE -> true
            else -> false
        }
    }

    private fun isLeaves(material: Material): Boolean {
        return material.name.endsWith("_LEAVES", ignoreCase = true)
    }

    private fun handleException(e: Exception, message: String) {
        // 记录错误信息而不直接禁用插件
        logger.severe("$message: ${e.message}")
        e.printStackTrace()

        // 如果是在特定情况下发生的异常，可以根据情况决定是否需要做额外的操作
        if (message.contains("when prepare 'Middle'")) {  // 示例条件判断
            // 可以在这里添加针对初始化中心位置失败时的具体处理
        } else if (message.contains("while handling first join event for player")) {
            // 对于首次加入事件处理中遇到的问题，可能不需要特别操作
        }
    }

    private fun logStatus() {
        logger.info("Scanned: $scannedChunks, Safe Spawns: $safeSpawnCount, TPS: ${server.tps[0]}")
        if (scannedChunks >= scanLimit) {
            logger.info("Scan completed. Found $safeSpawnCount safe spawn locations.")
        }
    }
}