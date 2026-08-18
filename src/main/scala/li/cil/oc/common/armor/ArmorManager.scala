package li.cil.oc.common.armor

import com.google.common.cache.{CacheBuilder, RemovalListener, RemovalNotification}
import com.google.common.collect.ImmutableMap
import com.mojang.blaze3d.platform.InputConstants
import li.cil.oc.Settings
import li.cil.oc.client.PacketSender.sendArmorInteraction
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.util.ItemUtils
import net.minecraft.client.{KeyMapping, Minecraft}
import net.minecraft.client.server.IntegratedServer
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import net.neoforged.bus.api.{IEventBus, SubscribeEvent}
import net.neoforged.neoforge.client.event.{ClientTickEvent, RegisterKeyMappingsEvent}
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks
import org.lwjgl.glfw.GLFW

import java.util.UUID
import java.util.concurrent.{Callable, TimeUnit}
import scala.collection.JavaConverters.asJavaIterable
import scala.jdk.CollectionConverters.{IterableHasAsScala, MapHasAsScala}

object ArmorManager {

  val armorKeyMapping = new KeyMapping(
    "key.opencomputers.armor_gui",
    InputConstants.Type.KEYSYM,
    GLFW.GLFW_KEY_O,
    "key.categories.opencomputers"
  )

  def init(modEventBus: IEventBus): Unit = {
    NeoForge.EVENT_BUS.register(this)
    modEventBus.addListener(this.onRegisterKeyMappings)
  }

  private def onRegisterKeyMappings(event: RegisterKeyMappingsEvent): Unit = {
    event.register(armorKeyMapping)
  }

  // ---------------------------------------------------------------- //

  def getPieceId(stack: ItemStack): Option[String] = {
    val tag = ItemUtils.getTag(stack)
    if (tag != null && tag.contains(Settings.namespace + "armor_piece_id", Tag.TAG_STRING)) {
      Some(tag.getString(Settings.namespace + "armor_piece_id"))
    } else None
  }

  def getOrCreatePieceId(stack: ItemStack): String = {
    var id: String = null
    CustomData.update(DataComponents.CUSTOM_DATA, stack, data => {
      if (!data.contains(Settings.namespace + "armor_piece_id")) {
        data.putString(Settings.namespace + "armor_piece_id", UUID.randomUUID().toString)
      }
      id = data.getString(Settings.namespace + "armor_piece_id")
    })
    id
  }

  def computeArmorChecksum(player: Player): String = {
    val slots = List(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    val pieceIds = slots.map { slot =>
      val stack = player.getItemBySlot(slot)
      if (!stack.isEmpty && stack.has(OCComponents.CONTENTS)) {
        if (player.level.isClientSide) {
          getPieceId(stack).getOrElse("unassigned")
        } else {
          getOrCreatePieceId(stack)
        }
      } else {
        "none"
      }
    }

    pieceIds.mkString(":")
  }

  // ---------------------------------------------------------------- //

  def get(player: Player): Option[ArmorWrapper] = {
    val chestStack = player.getItemBySlot(EquipmentSlot.CHEST)
    if (chestStack.isEmpty || !chestStack.has(OCComponents.CONTENTS)) {
      None
    } else {
      val currentChecksum = computeArmorChecksum(player)

      val wrapperOpt = if (player.level.isClientSide) Client.get(chestStack, player, currentChecksum)
      else Server.get(chestStack, player, currentChecksum)

      wrapperOpt.map { wrapper =>
        if (wrapper.checksum != currentChecksum) {
          invalidateWrapper(player, chestStack)
          if (player.level.isClientSide) Client.get(chestStack, player, currentChecksum).orNull
          else Server.get(chestStack, player, currentChecksum).orNull
        } else {
          wrapper
        }
      }.filter(_ != null)
    }
  }

  private def invalidateWrapper(player: Player, chestStack: ItemStack): Unit = {
    val cache = if (player.level.isClientSide) Client else Server
    val chestId = if (player.level.isClientSide) getPieceId(chestStack) else Some(getOrCreatePieceId(chestStack))

    chestId.foreach { key =>
      cache.invalidate(key)
    }
  }

  // ---------------------------------------------------------------- //

  @SubscribeEvent
  def onLevelSave(e: LevelEvent.Save): Unit = {
    Server.saveAll(e.getLevel.asInstanceOf[Level])
  }

  @SubscribeEvent
  def onPlayerSave(e: PlayerEvent.SaveToFile): Unit = {
    Server.save(e.getEntity)
  }

  @SubscribeEvent
  def onLevelUnload(e: LevelEvent.Unload): Unit = {
    Client.clear(e.getLevel.asInstanceOf[Level])
    Server.clear(e.getLevel.asInstanceOf[Level])
  }

  @SubscribeEvent
  def onClientTick(e: ClientTickEvent.Pre): Unit = {
    Client.cleanUp()
    ServerLifecycleHooks.getCurrentServer match {
      case integrated: IntegratedServer if Minecraft.getInstance.isPaused =>
        Client.keepAlive()
        Server.keepAlive()
      case _ =>
    }

    val mc = Minecraft.getInstance()
    val player = mc.player

    if (player != null && mc.level != null) {
      while (armorKeyMapping.consumeClick()) {
        if (mc.screen == null) {
          get(player).foreach(_.interact(mc.level, player))
          sendArmorInteraction()
        }
      }

      get(player).foreach(_.update(mc.level, player))
    }
  }

  @SubscribeEvent
  def onServerTick(e: ServerTickEvent.Pre): Unit = {
    Server.cleanUp()
    val server = e.getServer
    for (player <- server.getPlayerList.getPlayers.asScala) {
      get(player).foreach(_.update(player.level, player))
    }
  }

  def countArmor(player: Player): Int = {
    List(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).count { slot =>
      val stack = player.getItemBySlot(slot)
      !stack.isEmpty && stack.has(OCComponents.CONTENTS)
    }
  }

  // ---------------------------------------------------------------- //

  abstract class Cache extends Callable[ArmorWrapper] with RemovalListener[String, ArmorWrapper] {
    val cache: com.google.common.cache.Cache[String, ArmorWrapper] = CacheBuilder.newBuilder()
      .expireAfterAccess(timeout, TimeUnit.SECONDS)
      .removalListener(this)
      .asInstanceOf[CacheBuilder[String, ArmorWrapper]]
      .build[String, ArmorWrapper]()

    protected def timeout: Long = 10

    private var currentHolder: Player = _
    private var currentChecksum: String = _

    def get(chestStack: ItemStack, holder: Player, checksum: String): Option[ArmorWrapper] = {
      val id = if (holder.level.isClientSide) getPieceId(chestStack) else Some(getOrCreatePieceId(chestStack))

      id.map { key =>
        cache.synchronized {
          currentHolder = holder
          currentChecksum = checksum

          var wrapper = cache.get(key, this)

          // Force reload on level change (e.g. dimensions)
          if (holder.level != wrapper.getEnvironmentLevel) {
            wrapper.writeToNBT(holder.registryAccess())
            wrapper.autoSave = false
            cache.invalidate(key)
            cache.cleanUp()
            wrapper = cache.get(key, this)
          }

          currentHolder = null
          currentChecksum = null
          wrapper.player = holder
          wrapper
        }
      }
    }

    override def call(): ArmorWrapper = {
      val wrapper = new ArmorWrapper(currentHolder)
      wrapper.checksum = currentChecksum
      wrapper
    }

    def invalidate(key: String): Unit = {
      cache.synchronized {
        Option(cache.getIfPresent(key)).foreach { wrapper =>
          wrapper.autoSave = true
          cache.invalidate(key)
          cache.cleanUp()
        }
      }
    }

    override def onRemoval(e: RemovalNotification[String, ArmorWrapper]): Unit = {
      val wrapper = e.getValue
      if (wrapper != null && wrapper.node != null) {
        if (wrapper.autoSave) wrapper.writeToNBT(wrapper.player.registryAccess())
        wrapper.machine.stop()
        if (wrapper.machine.node != null && wrapper.machine.node.network != null) {
          for (node <- wrapper.machine.node.network.nodes.asScala) {
            node.remove()
          }
        }
        wrapper.setChanged()
      }
    }

    def clear(level: Level): Unit = {
      cache.synchronized {
        val wrappersInWorld = cache.asMap.asScala.filter(_._2.getEnvironmentLevel == level)
        cache.invalidateAll(asJavaIterable(wrappersInWorld.keys))
        cache.cleanUp()
      }
    }

    def cleanUp(): Unit = {
      cache.synchronized(cache.cleanUp())
    }

    def keepAlive(): ImmutableMap[String, ArmorWrapper] = {
      cache.getAllPresent(asJavaIterable(cache.asMap.keySet().asScala))
    }
  }

  object Client extends Cache {
    override protected def timeout: Long = 5

    def getWeak(chestStack: ItemStack): Option[ArmorWrapper] = {
      getPieceId(chestStack).flatMap { id =>
        val map = cache.asMap
        if (map.containsKey(id)) Option(map.get(id)) else None
      }
    }
  }

  object Server extends Cache {
    def save(player: Player): Unit = {
      cache.synchronized {
        for (wrapper <- cache.asMap.values().asScala if wrapper.player == player) {
          wrapper.writeToNBT(player.registryAccess())
        }
      }
    }

    def saveAll(level: Level): Unit = {
      cache.synchronized {
        for (wrapper <- cache.asMap.values().asScala if wrapper.getEnvironmentLevel == level) {
          wrapper.writeToNBT(wrapper.player.registryAccess())
        }
      }
    }
  }
}
