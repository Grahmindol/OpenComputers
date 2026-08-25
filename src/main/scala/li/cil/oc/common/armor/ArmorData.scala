package li.cil.oc.common.armor

import li.cil.oc.common.item.data.ItemMachineData
import li.cil.oc.util.ExtendedDataComponentHolder._
import net.minecraft.core.component.{DataComponentHolder, DataComponents}
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.DyedItemColor
import net.neoforged.neoforge.common.MutableDataComponentHolder

class ArmorData extends ItemMachineData {
  def this(stack: ItemStack) = {
    this()
    loadData(stack)
  }

  var color = -1

  override def loadData(holder: DataComponentHolder): Unit = {
    super.loadData(holder)
    color = holder.getComponent(DataComponents.DYED_COLOR).map(_.rgb()) getOrElse -1
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    super.saveData(holder)
    holder.setComponent(DataComponents.DYED_COLOR, new DyedItemColor(color, true))
  }
}
