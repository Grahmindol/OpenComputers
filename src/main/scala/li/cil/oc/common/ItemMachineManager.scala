package li.cil.oc.common

import com.google.common.cache.{CacheBuilder, RemovalListener, RemovalNotification}
import com.google.common.collect.ImmutableMap
import li.cil.oc.Settings
import li.cil.oc.client.PacketSender
import li.cil.oc.util.ItemUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.{Item, ItemStack}
import net.minecraft.world.level.Level
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks

import java.util.UUID
import java.util.concurrent.{Callable, TimeUnit}
import scala.annotation.unused
import scala.jdk.CollectionConverters.{IterableHasAsJava, IterableHasAsScala, MapHasAsScala}

object ItemMachineManager {
  private type WrapperFactory = (ItemStack, Player) => ItemMachineWrapper
  private var factories: Map[Item, WrapperFactory] = Map.empty

  def register(item: Item, factory: WrapperFactory): Unit = {
    factories += (item -> factory)
  }

  def get(stack: ItemStack, holder: Player): ItemMachineWrapper = {
    if (holder.level.isClientSide) Client.get(stack, holder)
    else Server.get(stack, holder)
  }

  // -------------------------------------------------------------- //

  def getId(stack: ItemStack): Option[String] = {
    val tag = ItemUtils.getTag(stack)
    if (tag != null && tag.contains(Settings.namespace + "item_state_id", Tag.TAG_STRING)) {
      Some(tag.getString(Settings.namespace + "item_state_id"))
    }
    else None
  }

  def getOrCreateId(stack: ItemStack): String = {
    var id: String = null
    CustomData.update(DataComponents.CUSTOM_DATA, stack, data => {
      if (!data.contains(Settings.namespace + "item_state_id")) {
        data.putString(Settings.namespace + "item_state_id", UUID.randomUUID().toString)
      }
      id = data.getString(Settings.namespace + "item_state_id")
    })

    id
  }

  // -------------------------------------------------------------- //

  @SubscribeEvent
  @unused
  def onLevelSave(e: LevelEvent.Save): Unit = Server.saveAll(e.getLevel.asInstanceOf[Level])

  @SubscribeEvent
  @unused
  def onPlayerSave(e: PlayerEvent.SaveToFile): Unit = Server.save(e.getEntity)

  @SubscribeEvent
  @unused
  def onLevelUnload(e: LevelEvent.Unload): Unit = {
    Client.clear(e.getLevel.asInstanceOf[Level])
    Server.clear(e.getLevel.asInstanceOf[Level])
  }

  @SubscribeEvent
  @unused
  def onClientTick(@unused e: ClientTickEvent.Pre): Unit = {
    Client.cleanUp()
    ServerLifecycleHooks.getCurrentServer match {
      case _: IntegratedServer if Minecraft.getInstance.isPaused =>
        Client.keepAlive()
        Server.keepAlive()
      case _ =>
    }
  }

  @SubscribeEvent
  @unused
  def onServerTick(@unused e: ServerTickEvent.Pre): Unit = Server.cleanUp()

  // -------------------------------------------------------------- //

  abstract class Cache extends Callable[ItemMachineWrapper] with RemovalListener[String, ItemMachineWrapper] {
    val cache: com.google.common.cache.Cache[String, ItemMachineWrapper] = com.google.common.cache.CacheBuilder.newBuilder()
      .expireAfterAccess(timeout, TimeUnit.SECONDS)
      .removalListener(this)
      .asInstanceOf[CacheBuilder[String, ItemMachineWrapper]]
      .build[String, ItemMachineWrapper]()

    protected def timeout = 10

    private var currentStack: ItemStack = _
    private var currentHolder: Player = _

    def get(stack: ItemStack, holder: Player): ItemMachineWrapper = {
      val id = getOrCreateId(stack)
      cache.synchronized {
        currentStack = stack
        currentHolder = holder

        if (holder.level.isClientSide) {
          Client.getWeak(stack) match {
            case Some(weak) =>
              val timesChanged = holder.getInventory.getTimesChanged
              if (timesChanged != weak.timesChanged) {
                if (!weak.isDirty) {
                  weak.isDirty = true
                  PacketSender.sendMachineItemStateRequest(stack, holder.level.registryAccess())
                }
                weak.timesChanged = timesChanged
              }
            case _ =>
          }
        }

        var wrapper = cache.get(id, this)

        if (holder.level != wrapper.getEnvironmentLevel) {
          wrapper.writeToNBT(holder.registryAccess())
          wrapper.autoSave = false
          cache.invalidate(id)
          cache.cleanUp()
          wrapper = cache.get(id, this)
        }

        currentStack = null
        currentHolder = null

        wrapper.stack = stack
        wrapper.player = holder
        wrapper
      }
    }

    def call: ItemMachineWrapper = {
      factories.get(currentStack.getItem) match {
        case Some(factory) =>
          factory(currentStack, currentHolder)
        case None =>
          throw new Error("Wrapper not found !"); // TODO : handle this case
      }
    }

    def onRemoval(e: RemovalNotification[String, ItemMachineWrapper]): Unit = {
      val state = e.getValue
      if (state.node != null) {
        if (state.autoSave) state.writeToNBT(state.player.registryAccess())
        state.machine.stop()
        for (node <- state.machine.node.network.nodes.asScala) {
          node.remove()
        }
        state.setChanged()
      }
    }

    def clear(level: Level): Unit = {
      cache.synchronized {
        val inWorld = cache.asMap.asScala.filter(_._2.getEnvironmentLevel == level)
        cache.invalidateAll(inWorld.keys.asJava)
        cache.cleanUp()
      }
    }

    def cleanUp(): Unit = cache.synchronized(cache.cleanUp())

    def keepAlive(): ImmutableMap[String, ItemMachineWrapper] = cache.getAllPresent(cache.asMap.asScala.keys.asJava)
  }

  object Client extends Cache {
    override protected def timeout = 5

    def getWeak(stack: ItemStack): Option[ItemMachineWrapper] = {
      val key = getId(stack)
      if (key.nonEmpty) {
        val map = cache.asMap
        if (map.containsKey(key)) return map.entrySet.asScala.find(_.getKey == key.get).map(_.getValue)
      }
      None
    }

    def get(stack: ItemStack): Option[ItemMachineWrapper] = {
      val id = getId(stack)
      if (id.nonEmpty) cache.synchronized(Option(cache.getIfPresent(id)))
      else None
    }
  }

  object Server extends Cache {
    def save(player: Player): Unit = {
      cache.synchronized {
        for (wrapper <- cache.asMap.asScala.values if wrapper.player == player) {
          wrapper.writeToNBT(player.registryAccess())
        }
      }
    }

    def saveAll(level: Level): Unit = {
      cache.synchronized {
        for (wrapper <- cache.asMap.asScala.values if wrapper.getEnvironmentLevel == level) {
          wrapper.writeToNBT(wrapper.player.registryAccess())
        }
      }
    }
  }
}
