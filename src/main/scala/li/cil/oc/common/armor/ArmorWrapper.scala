package li.cil.oc.common.armor

import li.cil.oc.{Localization, OpenComputers, Settings, api, client, server}
import li.cil.oc.api.driver.item.Container
import li.cil.oc.api.{Driver, Machine}
import li.cil.oc.api.machine.MachineHost
import li.cil.oc.api.network.{Connector, Message, Node}
import li.cil.oc.client.gui
import li.cil.oc.common.{Slot, Tier}
import li.cil.oc.common.container.ComponentInventory
import li.cil.oc.server.component.{Armor => ArmorComponent}
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.MutableDataComponentHolder

import scala.jdk.CollectionConverters.IterableHasAsJava

class ArmorWrapper(var player: Player) extends ComponentInventory with MachineHost {
  var checksum: String = ""
  val data = new ArmorData(player)

  val getEnvironmentLevel: Level = player.level

  val armor: ArmorComponent = if (getEnvironmentLevel.isClientSide) null else new ArmorComponent(this)

  // Serveur uniquement
  private var lastRunning = false

  var autoSave = true

  // Client uniquement
  private var isInitialized = false

  def chestStack: ItemStack = player.getItemBySlot(EquipmentSlot.CHEST)

  // ----------------------------------------------------------------------- //

  def readFromNBT(provider: HolderLookup.Provider): Unit = {
    data.loadData(player)
    if (!getEnvironmentLevel.isClientSide && !chestStack.isEmpty) {
      armor.loadData(chestStack)
      machine.loadData(chestStack)
    }
  }

  def writeToNBT(provider: HolderLookup.Provider): Unit = {
    saveComponents()
    data.saveData(player)
    if (!getEnvironmentLevel.isClientSide && !chestStack.isEmpty) {
      armor.saveData(chestStack)
      machine.saveData(chestStack)
    }
  }

  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node): Unit = {
    if (node == this.node) {
      connectComponents()
      if (armor != null && armor.node != null) {
        node.connect(armor.node)
      }
    } else node.host match {
      case buffer: api.internal.TextBuffer =>
        buffer.setMaximumColorDepth(api.internal.TextBuffer.ColorDepth.FourBit)
        buffer.setMaximumResolution(80, 25)
      case _ =>
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
      if (armor != null && armor.node != null) {
        armor.node.remove()
      }
    }
  }

  override def onMessage(message: Message): Unit = {}

  override def node: Node = Option(machine).fold(null: Node)(_.node)

  // ----------------------------------------------------------------------- //

  override def host: ArmorWrapper = this

  // ----------------------------------------------------------------------- //

  lazy val machine: api.machine.Machine = if (getEnvironmentLevel.isClientSide) null else Machine.create(this)

  def containerSlotType: String =
    if (data.container.isEmpty) Slot.None
    else Option(Driver.driverFor(data.container, getClass)) match {
      case Some(driver: Container) => driver.providedSlot(data.container)
      case _ => Slot.None
    }

  def containerSlotTier: Int =
    if (data.container.isEmpty) Tier.None
    else Option(Driver.driverFor(data.container, getClass)) match {
      case Some(driver: Container) => driver.providedTier(data.container)
      case _ => Tier.None
    }

  override def internalComponents(): java.lang.Iterable[ItemStack] = (0 until getContainerSize).collect {
    case slot if !getItem(slot).isEmpty && isComponentSlot(slot, getItem(slot)) => getItem(slot)
  }.asJava

  override def componentSlot(address: String): Int =
    componentSlots.indexWhere(_.exists(env => env.node != null && env.node.address == address))

  override def onMachineConnect(node: Node): Unit = onConnect(node)

  override def onMachineDisconnect(node: Node): Unit = onDisconnect(node)

  // ----------------------------------------------------------------------- //

  override def getContainerSize: Int = items.length

  override def stillValid(player: Player): Boolean = machine != null && machine.canInteract(player.getName.getString)

  override def setChanged(): Unit = {
    saveComponents()
    data.saveData(player)
    player.getInventory.setChanged()
  }

  // ----------------------------------------------------------------------- //

  def items: Array[ItemStack] = data.items

  // ----------------------------------------------------------------------- //

  override def xPosition: Double = player.getX

  override def yPosition: Double = player.getY + player.getEyeHeight

  override def zPosition: Double = player.getZ

  override def markChanged(): Unit = {}

  // ----------------------------------------------------------------------- //

  def update(level: Level, player: Player): Unit = {
    this.player = player

    if (!isInitialized) {
      isInitialized = true
      connectComponents()
      OpenComputers.log.warn("Wrapper init ! : client side : ", level.isClientSide)
      componentSlots collect {
        case Some(buffer: api.internal.TextBuffer) =>
          buffer.setMaximumColorDepth(api.internal.TextBuffer.ColorDepth.FourBit)
          buffer.setMaximumResolution(80, 25)
      }

      // Requête de synchronisation de l'état envoyée au serveur avec le stack du Plastron
      if (!chestStack.isEmpty) {
        client.PacketSender.sendMachineItemStateRequest(chestStack, level.registryAccess())
      }
    }

    if (!level.isClientSide && machine != null) {
      machine.node.asInstanceOf[Connector].changeBuffer(Double.PositiveInfinity)
      machine.update()
      updateComponents()

      data.isRunning = machine.isRunning
      if (armor != null && armor.node != null) {
        data.energy = armor.node.globalBuffer()
        data.maxEnergy = armor.node.globalBufferSize()
      }

      if (lastRunning != machine.isRunning) {
        lastRunning = machine.isRunning
        setChanged()

        player match {
          case mp: ServerPlayer if !chestStack.isEmpty =>
            server.PacketSender.sendMachineItemState(mp, chestStack, machine.isRunning)
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

  // ----------------------------------------------------------------------- //

  def interact(level: Level, player: Player) = {
    if (!level.isClientSide) {
      machine.start()
      machine.lastError match {
        case message if message != null => player.sendSystemMessage(Localization.Analyzer.LastError(message))
        case _ =>
      }
    }else {
      componentSlots.collectFirst {
        case Some(buffer: api.internal.TextBuffer) => buffer
      } match {
        case Some(buffer: api.internal.TextBuffer) => Minecraft.getInstance.pushGuiLayer(new gui.Screen(buffer, true, () => true, () => buffer.isRenderingEnabled))
        case _ =>
      }
    }
  }


  // ----------------------------------------------------------------------- //

  override def loadData(holder: DataComponentHolder): Unit = {
    data.loadData(player)
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    saveComponents()
    data.saveData(player)
  }
}
