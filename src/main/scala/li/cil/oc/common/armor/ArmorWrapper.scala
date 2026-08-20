package li.cil.oc.common.armor

import li.cil.oc._
import li.cil.oc.api.Driver
import li.cil.oc.api.driver.item.Container
import li.cil.oc.common.{ItemStateWrapper, Slot, Tier}
import li.cil.oc.integration.opencomputers.DriverScreen
import li.cil.oc.server.component.{Armor => ArmorComponent}
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.neoforge.common.MutableDataComponentHolder

class ArmorWrapper(player: Player) extends ItemStateWrapper(player.getItemBySlot(EquipmentSlot.CHEST), player) {

  var checksum: String = ""

  val data = new ArmorData(player)
  val internalComponent: ArmorComponent = if (getEnvironmentLevel.isClientSide) null else new ArmorComponent(this)

  // ----------------------------------------------------------------------- //

  readFromNBT(player.registryAccess())

  if (!getEnvironmentLevel.isClientSide) {
    api.Network.joinNewNetwork(machine.node)

    //val charge = Math.max(0, data.energy - internalComponent.node.globalBuffer)
    internalComponent.node.changeBuffer(Double.PositiveInfinity)

    writeToNBT(player.registryAccess())
  }

  // ----------------------------------------------------------------------- //

  override def isCreative: Boolean = true

  def items: Array[ItemStack] = data.items

  override def host: ArmorWrapper = this

  // ----------------------------------------------------------------------- //

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

  override def onInit(level: Level,player: Player): Unit = {
    OpenComputers.log.info(s"ArmorWrapper initialization")
  }

  override def onDataUpdate(level: Level,player: Player): Unit = {
    data.isRunning = machine.isRunning
    data.energy = internalComponent.node.globalBuffer()
    data.maxEnergy = internalComponent.node.globalBufferSize()

    componentSlots collect {
      case Some(buffer: api.internal.TextBuffer) =>
        buffer.setMaximumColorDepth(api.internal.TextBuffer.ColorDepth.FourBit)
        buffer.setMaximumResolution(80, 25)
    }
  }


  // ----------------------------------------------------------------------- //

  override def loadData(holder: DataComponentHolder): Unit = {
    super.loadData(holder)
    data.loadData(player)
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    super.saveData(holder)
    data.saveData(player)
  }

}
