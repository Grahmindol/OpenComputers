package li.cil.oc.common.armor

import com.mojang.blaze3d.platform.InputConstants
import li.cil.oc.Settings
import li.cil.oc.client.PacketSender.sendArmorInteraction
import li.cil.oc.common.ItemStateManager
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.util.ItemUtils
import net.minecraft.client.{KeyMapping, Minecraft}
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.Tag
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import net.neoforged.bus.api.{IEventBus, SubscribeEvent}
import net.neoforged.neoforge.client.event.{ClientTickEvent, RegisterKeyMappingsEvent}
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.lwjgl.glfw.GLFW

import java.util.UUID
import scala.annotation.unused
import scala.jdk.CollectionConverters.IterableHasAsScala

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

  private def getPieceId(stack: ItemStack): Option[String] = {
    val tag = ItemUtils.getTag(stack)
    if (tag != null && tag.contains(Settings.namespace + "armor_piece_id", Tag.TAG_STRING)) {
      Some(tag.getString(Settings.namespace + "armor_piece_id"))
    } else None
  }

  private def getOrCreatePieceId(stack: ItemStack): String = {
    var id: String = null
    CustomData.update(DataComponents.CUSTOM_DATA, stack, data => {
      if (!data.contains(Settings.namespace + "armor_piece_id")) {
        data.putString(Settings.namespace + "armor_piece_id", UUID.randomUUID().toString)
      }
      id = data.getString(Settings.namespace + "armor_piece_id")
    })
    id
  }

  private def computeArmorChecksum(player: Player): String = {
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
      //val currentChecksum = computeArmorChecksum(player)

      ItemStateManager.get(chestStack, player) match {
        case wrapper: ArmorWrapper => Some (wrapper)
        case _ => None
      }
    }
  }


  // ---------------------------------------------------------------- //

  @SubscribeEvent
  @unused
  def onClientTick(e: ClientTickEvent.Pre): Unit = {
    val mc = Minecraft.getInstance()
    val player = mc.player

    if (player != null && mc.level != null) {
      while (armorKeyMapping.consumeClick()) {
        if (mc.screen == null) {
          sendArmorInteraction()
          get(player).foreach(_.interact(mc.level, player))
        }
      }

      get(player).foreach(_.update(mc.level, player))
    }
  }

  @SubscribeEvent
  def onServerTick(e: ServerTickEvent.Pre): Unit = {
    val server = e.getServer
    for (player <- server.getPlayerList.getPlayers.asScala) {
      get(player).foreach(_.update(player.level, player))
    }
  }
}
