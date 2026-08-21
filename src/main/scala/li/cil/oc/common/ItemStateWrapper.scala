package li.cil.oc.common

import li.cil.oc.api.Machine
import li.cil.oc.api.machine.MachineHost
import li.cil.oc.api.network.{Connector, Message, Node}
import li.cil.oc.api.prefab.AbstractManagedEnvironment
import li.cil.oc.client.gui
import li.cil.oc.common.container.ComponentInventory
import li.cil.oc._
import li.cil.oc.common.item.data.TabletData
import li.cil.oc.common.menu.MenuTypes
import li.cil.oc.server.PacketSender
import li.cil.oc.util.RotationHelper
import net.minecraft.client.Minecraft
import net.minecraft.core.{Direction, HolderLookup}
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.MenuProvider
import net.minecraft.world.entity.player.{Inventory, Player}
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.MutableDataComponentHolder

import scala.jdk.CollectionConverters.IterableHasAsJava

abstract class ItemStateWrapper(var stack: ItemStack, var player: Player ) extends ComponentInventory with MachineHost with MenuProvider with  api.internal.Tablet{
  // Remember our *original* level, so we know which tablets to clear on dimension
  // changes of players holding tablets - since the player entity instance may be
  // kept the same and components are not required to properly handle level changes.
  val getEnvironmentLevel: Level = player.level

  val data = new TabletData()
  lazy val machine: api.machine.Machine = if (getEnvironmentLevel.isClientSide) null else Machine.create(this)

  def isCreative: Boolean

  // TabletComponent for tablet....
  val internalComponent: AbstractManagedEnvironment

  //// Client side only
  var isInitialized: Boolean = false

  var timesChanged: Int = 0

  var isDirty: Boolean = true
  ////

  // Server side only
  var lastRunning = false

  var autoSave = true


  def items: Array[ItemStack]


  def readFromNBT(provider: HolderLookup.Provider): Unit = {
    loadData(stack)
    if (!getEnvironmentLevel.isClientSide) {
      internalComponent.loadData(stack)
      machine.loadData(stack)
    }
  }

  def writeToNBT(provider: HolderLookup.Provider): Unit = {
    saveData(stack)
    if (!getEnvironmentLevel.isClientSide) {
      internalComponent.saveData(stack)
      machine.saveData(stack)
    }
  }

  // ----------------------------------------------------------------------- //

  def facing: Direction =
    RotationHelper.fromYaw(player.getYRot)

  def toLocal(value: Direction): Direction =
    RotationHelper.toLocal(Direction.NORTH, facing, value)

  def toGlobal(value: Direction): Direction =
    RotationHelper.toGlobal(Direction.NORTH, facing, value)



  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node): Unit = {
    if (node == this.node) {
      connectComponents()
      node.connect(internalComponent.node)
    }
  }

  override protected def connectItemNode(node: Node): Unit = {
    super.connectItemNode(node)
    if (node != null) node.host match {
      case buffer: api.internal.TextBuffer => componentSlots collect {
        case Some(keyboard: api.internal.Keyboard) => buffer.node.connect(keyboard.node)
      }
      case keyboard: api.internal.Keyboard => componentSlots collect {
        case Some(buffer: api.internal.TextBuffer) => keyboard.node.connect(buffer.node)
      }
      case _ =>
    }
  }

  override def onDisconnect(node: Node): Unit = {
    if (node == this.node) {
      disconnectComponents()
      internalComponent.node.remove()
    }
  }

  override def onMessage(message: Message): Unit = {}

  override def getContainerSize: Int = items.length

  override def stillValid(player: Player): Boolean = machine != null && machine.canInteract(player.getName.getString)

  override def setChanged(): Unit = {
    saveData(stack)
    player.getInventory.setChanged()
  }

  // ----------------------------------------------------------------------- //

  override def xPosition: Double = player.getX

  override def yPosition: Double = player.getY + player.getEyeHeight

  override def zPosition: Double = player.getZ

  override def markChanged(): Unit = {}

  // ----------------------------------------------------------------------- //

  def containerSlotType: String

  def containerSlotTier: Int

  override def internalComponents(): java.lang.Iterable[ItemStack] = (0 until getContainerSize).collect {
    case slot if !getItem(slot).isEmpty && isComponentSlot(slot, getItem(slot)) => getItem(slot)
  }.asJava

  override def componentSlot(address: String): Int = componentSlots.indexWhere(_.exists(env => env.node != null && env.node.address == address))

  override def onMachineConnect(node: Node): Unit = onConnect(node)

  override def onMachineDisconnect(node: Node): Unit = onDisconnect(node)

  // ----------------------------------------------------------------------- //

  override def node: Node = Option(machine).fold(null: Node)(_.node)

  // ----------------------------------------------------------------------- //

  def onInit(level: Level, player: Player): Unit = {}

  def onDataUpdate(level: Level, player: Player): Unit = {}

  def update(level: Level, player: Player): Unit = {
    this.player = player
    if (!isInitialized && level.isClientSide) {
      isInitialized = true
      // This delayed initialization on the client side is required to allow
      // the server to set up the tablet wrapper first (since packets generated
      // in the component setup would otherwise be queued before the events that
      // caused this wrapper's initialization).
      connectComponents()
      onInit(level, player)

      client.PacketSender.sendMachineItemStateRequest(stack, level.registryAccess())
    }

    if (!level.isClientSide) {
      if (isCreative && level.getGameTime % Settings.get.tickFrequency == 0) {
        machine.node.asInstanceOf[Connector].changeBuffer(Double.PositiveInfinity)
      }
      machine.update()
      updateComponents()
      onDataUpdate(level, player)

      if (lastRunning != machine.isRunning) {
        lastRunning = machine.isRunning
        setChanged()

        player match {
          case mp: ServerPlayer => server.PacketSender.sendMachineItemState(mp, stack, machine.isRunning)
          case _ =>
        }

        if (machine.isRunning) {
          componentSlots collect {
            case Some(buffer: api.internal.TextBuffer) =>
              buffer.setPowerState(true)
          }
        }
      }
    }
  }

  def interact(level: Level, player: Player) = {
    if (player.isSecondaryUseActive) {
      if (!level.isClientSide) {
        player match {
          case srvPlr: ServerPlayer => MenuTypes.openTabletGui(srvPlr, this)
          case _ =>
        }
      }
    }
    else {
      if (!level.isClientSide) {
        OpenComputers.log.info("interaction !!! (server)")
        machine.start()
        machine.lastError match {
          case message if message != null => player.sendSystemMessage(Localization.Analyzer.LastError(message))
          case _ =>
        }
      }
      else {
        OpenComputers.log.info("interaction !!! (client)")
        componentSlots.collectFirst {
          case Some(buffer: api.internal.TextBuffer) => buffer
        } match {
          case Some(buffer: api.internal.TextBuffer) =>
            OpenComputers.log.info(buffer)
            Minecraft.getInstance.pushGuiLayer(new gui.Screen(buffer, true, () => true, () => buffer.isRenderingEnabled))
          case _ =>
        }
      }
    }
  }

  // ----------------------------------------------------------------------- //

  override def loadData(holder: DataComponentHolder): Unit = {
    data.loadData(holder)
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    saveComponents()
    data.saveData(holder)
  }

  // ----------------------------------------------------------------------- //

  override def getDisplayName = getName

  override def createMenu(id: Int,playerInventory: Inventory,player: Player) =
    new menu.Tablet(
      id,
      playerInventory,
      stack,
      this,
      containerSlotType,
      containerSlotTier
    )
}
