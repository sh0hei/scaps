/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package scaps.webservice

import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config

case class WebserviceSettings(
  interface: String,
  port: Int,
  controlInterface: String,
  controlPort: Int,
  prodMode: Boolean,
  analyticsScript: String)

object WebserviceSettings {
  def fromApplicationConf =
    WebserviceSettings(ConfigFactory.load().getConfig("scaps.webservice"))

  def apply(conf: Config): WebserviceSettings =
    WebserviceSettings(
      conf.getString("interface"),
      conf.getInt("port"),
      conf.getString("control-interface"),
      conf.getInt("control-port"),
      conf.getBoolean("prod-mode"),
      conf.getString("analytics-script"))
}
