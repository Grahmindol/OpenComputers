package li.cil.oc.common.item

import com.google.common.cache.{CacheBuilder, RemovalListener, RemovalNotification}
import com.google.common.collect.ImmutableMap
import li.cil.oc.api.driver.item.Container
import li.cil.oc.api.machine.MachineHost
import li.cil.oc.api.network.{Connector, Message, Node}
import li.cil.oc.api.{Driver, Machine, internal}
import li.cil.oc.client.{KeyBindings, gui}
import li.cil.oc.common.container.ComponentInventory
import li.cil.oc.common.item.data.TabletData
import li.cil.oc.common.menu.MenuTypes
import li.cil.oc.common.{ItemStateManager, ItemStateWrapper, Slot, Tier, menu}
import li.cil.oc.integration.opencomputers.DriverScreen
import li.cil.oc.server.component.{Tablet => TabletComponent}
import li.cil.oc.util._
import li.cil.oc.{Constants, Localization, OpenComputers, Settings, api, client, server}
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.client.server.IntegratedServer
import net.minecraft.core.component.{DataComponentHolder, DataComponents}
import net.minecraft.core.{BlockPos, Direction, HolderLookup}
import net.minecraft.nbt.{CompoundTag, Tag}
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world._
import net.minecraft.world.entity.player.{Inventory, Player}
import net.minecraft.world.entity.{Entity, LivingEntity}
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.{Item, ItemStack}
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.MutableDataComponentHolder
import net.neoforged.neoforge.common.extensions.IItemExtension
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks

import java.util
import java.util.UUID
import java.util.concurrent.{Callable, TimeUnit}
import scala.collection.JavaConverters.asJavaIterable
import scala.collection.convert.ImplicitConversionsToScala._
import scala.jdk.CollectionConverters._

class Tablet(props: Properties) extends Item(props) with traits.SimpleItem with traits.Chargeable with IItemExtension {
  final val TimeToAnalyze = 10

  // ----------------------------------------------------------------------- //

  override protected def tooltipExtended(stack: ItemStack, tooltip: util.List[Component]): Unit = {
    if (KeyBindings.showExtendedTooltips) {
      val info = new TabletData(stack)
      // Ignore/hide the screen.
      val components = info.items.drop(1)
      if (components.length > 1) {
        for (curr <- Tooltip.get("server.Components")) {
          tooltip.add(Component.literal(curr).setStyle(Tooltip.DefaultStyle))
        }
        components.collect {
          case component if !component.isEmpty => tooltip.add(Component.literal("- " + component.getHoverName.getString).setStyle(Tooltip.DefaultStyle))
        }
      }
    }
  }

  override def verifyComponentsAfterLoad(stack: ItemStack): Unit = {
    super.verifyComponentsAfterLoad(stack)
    // FIXME: This is a horrible hack!
    stack.set(DataComponents.RARITY, Rarity.byTier(new TabletData(stack).tier))
  }

  override def isBarVisible(stack: ItemStack) = true

  override def getBarWidth(stack: ItemStack): Int = {
    if (stack.has(DataComponents.CUSTOM_DATA)) {
      val data = ItemStateManager.Client.getWeak(stack) match {
        case Some(wrapper) => wrapper.asInstanceOf[TabletWrapper].data
        case _ => new TabletData(stack)
      }
      val ratio = data.energy / data.maxEnergy
      Math.round(ratio * 13.0f).toInt
    }
    else 13
  }

  // ----------------------------------------------------------------------- //

  @OnlyIn(Dist.CLIENT)
  private def modelLocationFromState(running: Option[Boolean]) = {
    val suffix = running match {
      case Some(state) => if (state) "_on" else "_off"
      case _ => ""
    }
    ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(Settings.resourceDomain, Constants.ItemName.Tablet + suffix))
  }

  def canCharge(stack: ItemStack): Boolean = true

  def charge(stack: ItemStack, amount: Double, simulate: Boolean): Double = {
    if (amount < 0) return amount
    val data = new TabletData(stack)
    traits.Chargeable.applyCharge(amount, data.energy, data.maxEnergy, used => if (!simulate) {
      data.energy += used
      data.saveData(stack)
    })
  }

  // ----------------------------------------------------------------------- //

  override def inventoryTick(stack: ItemStack, level: Level, entity: Entity, slot: Int, selected: Boolean): Unit =
    entity match {
      case player: Player =>
        // Play an audio cue to let players know when they finished analyzing a block.
        if (level.isClientSide && player.getUseItemRemainingTicks == TimeToAnalyze && api.Items.get(player.getUseItem) == api.Items.get(Constants.ItemName.Tablet)) {
          Audio.play(player.getX.toFloat, player.getY.toFloat + 2, player.getZ.toFloat, ".")
        }
        ItemStateManager.get(stack, player).update(level, player)
      case _ =>
    }

  override def onItemUseFirst(stack: ItemStack, player: Player, level: Level, pos: BlockPos, side: Direction, hitX: Float, hitY: Float, hitZ: Float, hand: InteractionHand): InteractionResult = {
    Tablet.currentlyAnalyzing = Some((BlockPosition(pos, level), side, hitX, hitY, hitZ))
    super.onItemUseFirst(stack, player, level, pos, side, hitX, hitY, hitZ, hand)
  }

  override def onItemUse(stack: ItemStack, player: Player, position: BlockPosition, side: Direction, hitX: Float, hitY: Float, hitZ: Float): Boolean = {
    player.startUsingItem(if (player.getItemInHand(InteractionHand.MAIN_HAND) == stack) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND)
    true
  }

  @Deprecated
  override def use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder[ItemStack] =
    use(player.getItemInHand(hand), level, player)

  override def use(stack: ItemStack, level: Level, player: Player): InteractionResultHolder[ItemStack] = {
    player.startUsingItem(if (player.getItemInHand(InteractionHand.MAIN_HAND) == stack) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND)
    new InteractionResultHolder(InteractionResult.sidedSuccess(level.isClientSide), stack)
  }

  override def getUseDuration(stack: ItemStack, entity: LivingEntity): Int = 72000

  override def releaseUsing(stack: ItemStack, level: Level, entity: LivingEntity, duration: Int): Unit = {
    entity match {
      case player: Player =>
        val didAnalyze = getUseDuration(stack, entity) - duration >= TimeToAnalyze
        if (didAnalyze) {
          if (!level.isClientSide) {
            Tablet.currentlyAnalyzing match {
              case Some((position, side, hitX, hitY, hitZ)) => try {
                val computer = ItemStateManager.get(stack, player).machine
                if (computer.isRunning) {
                  val data = new CompoundTag()
                  computer.node.sendToReachable("tablet.use", data, stack, player, position, side, Float.box(hitX), Float.box(hitY), Float.box(hitZ))
                  if (!data.isEmpty) {
                    computer.signal("tablet_use", data)
                  }
                }
              }
              catch {
                case t: Throwable => OpenComputers.log.warn("Block analysis on tablet right click failed gloriously!", t)
              }
              case _ =>
            }
          }
        }
        else {
          if (player.isSecondaryUseActive) {
            if (!level.isClientSide) {
              player match {
                case srvPlr: ServerPlayer => MenuTypes.openTabletGui(srvPlr, ItemStateManager.get(stack, player))
                case _ =>
              }
            }
          }
          else {
            ItemStateManager.get(stack, player).interact(level, player)
          }
        }
      case _ =>
    }
  }

  override def maxCharge(stack: ItemStack): Double = new TabletData(stack).maxEnergy

  override def getCharge(stack: ItemStack): Double = new TabletData(stack).energy

  override def setCharge(stack: ItemStack, amount: Double): Unit = {
    val data = new TabletData(stack)
    data.energy = (0.0 max amount) min maxCharge(stack)
    data.saveData(stack)
  }
}

class TabletWrapper(stack: ItemStack, player: Player) extends ItemStateWrapper(stack, player) {

  val data = new TabletData()
  val internalComponent: TabletComponent = if (getEnvironmentLevel.isClientSide) null else new TabletComponent(this)

  // Allow T3 tablets to have 8-bit color since they use a T3 screen.
  // For balance/ergonomic reasons, tablets are always limited to T2
  // resolution of 80x25.
  lazy val colorDepth =
    if (data.tier >= Tier.Three)
      api.internal.TextBuffer.ColorDepth.EightBit
    else
      api.internal.TextBuffer.ColorDepth.FourBit

  // ----------------------------------------------------------------------- //

  readFromNBT(player.registryAccess())

  if (!getEnvironmentLevel.isClientSide) {
    api.Network.joinNewNetwork(machine.node)

    val charge = Math.max(0, data.energy - internalComponent.node.globalBuffer)
    internalComponent.node.changeBuffer(charge)

    writeToNBT(player.registryAccess())
  }

  // ----------------------------------------------------------------------- //

  def isCreative: Boolean = data.tier == Tier.Five

  override def items: Array[ItemStack] = data.items

  override def host: TabletWrapper = this


  // ----------------------------------------------------------------------- //

  def facing: Direction =
    RotationHelper.fromYaw(player.getYRot)

  def toLocal(value: Direction): Direction =
    RotationHelper.toLocal(Direction.NORTH, facing, value)

  def toGlobal(value: Direction): Direction =
    RotationHelper.toGlobal(Direction.NORTH, facing, value)

  // ----------------------------------------------------------------------- //

  def containerSlotType: String =
    if (data.container.isEmpty) {
      Slot.None
    } else {
      Option(Driver.driverFor(data.container, getClass)) match {
        case Some(driver: Container) =>
          driver.providedSlot(data.container)
        case _ =>
          Slot.None
      }
    }

  def containerSlotTier: Int =
    if (data.container.isEmpty) {
      Tier.None
    } else {
      Option(Driver.driverFor(data.container, getClass)) match {
        case Some(driver: Container) =>
          driver.providedTier(data.container)
        case _ =>
          Tier.None
      }
    }

  override def canPlaceItem(slot: Int, stack: ItemStack): Boolean =
    slot == getContainerSize - 1 &&
      (Option(Driver.driverFor(stack, getClass)) match {
        case Some(driver) =>
          // Same special cases, similar as in robot, but allow keyboards,
          // because clip-on keyboards kinda seem to make sense, I guess.
          driver != DriverScreen &&
            driver.slot(stack) == containerSlotType &&
            driver.tier(stack) <= containerSlotTier

        case _ =>
          false
      })

  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node): Unit = {
    super.onConnect(node)

    node.host match {
      case buffer: api.internal.TextBuffer =>
        buffer.setMaximumColorDepth(colorDepth)
        buffer.setMaximumResolution(80, 25)
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  override def onInit(level: Level,player: Player): Unit = {
    OpenComputers.log.info(s"TabletWrapper initialization")
    componentSlots collect {
      case Some(buffer: api.internal.TextBuffer) =>
        buffer.setMaximumColorDepth(colorDepth)
        buffer.setMaximumResolution(80, 25)
    }
  }

  override def onDataUpdate(level: Level,player: Player): Unit = {
    data.isRunning = machine.isRunning
    data.energy = internalComponent.node.globalBuffer()
    data.maxEnergy = internalComponent.node.globalBufferSize()
  }

  // ----------------------------------------------------------------------- //

  override def loadData(holder: DataComponentHolder): Unit = {
    super.loadData(holder)
    data.loadData(holder)
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    super.saveData(holder)
    data.saveData(holder)
  }
}

object Tablet {
  // This is super-hacky, but since it's only used on the client we get away
  // with storing context information for analyzing a block in the singleton.
  var currentlyAnalyzing: Option[(BlockPosition, Direction, Float, Float, Float)] = None

}
