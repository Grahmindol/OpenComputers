package li.cil.oc.common.item.data

import li.cil.oc.common.Tier
import li.cil.oc.common.datacomponents.OCComponents
import li.cil.oc.util.ExtendedDataComponentHolder._
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.MutableDataComponentHolder

class TabletData extends ItemMachineData {
  def this(stack: ItemStack) = {
    this()
    loadData(stack)
  }

  var tier: Int = Tier.One

  override def loadData(holder: DataComponentHolder): Unit = {
    super.loadData(holder)
    tier = holder.getComponent(OCComponents.TIER).map(_.toInt) getOrElse 0
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    super.saveData(holder)
    holder.setComponent(OCComponents.TIER, tier.toByte)
  }
}
