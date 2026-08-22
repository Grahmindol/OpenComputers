package li.cil.oc.common.armor

import li.cil.oc.api.Network
import li.cil.oc.api.driver.DeviceInfo
import li.cil.oc.api.driver.DeviceInfo.{DeviceAttribute, DeviceClass}
import li.cil.oc.api.network.{Node, Visibility}
import li.cil.oc.api.prefab.AbstractManagedEnvironment
import net.minecraft.world.item.armortrim.TrimPattern

import java.util
import scala.jdk.CollectionConverters.MapHasAsJava

class Trim(pattern :TrimPattern) extends AbstractManagedEnvironment with DeviceInfo {
  override val node: Node = Network.newNode(this, Visibility.Neighbors).
    create()

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Memory,
    DeviceAttribute.Description -> pattern.description().getString
  )

  override def getDeviceInfo: util.Map[String, String] = deviceInfo.asJava
}
