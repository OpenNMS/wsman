[![Build Status](https://travis-ci.org/OpenNMS/wsman.svg?branch=master)](https://travis-ci.org/OpenNMS/wsman) [![codecov.io](https://codecov.io/github/OpenNMS/wsman/coverage.svg?branch=master)](https://codecov.io/github/OpenNMS/wsman?branch=master)

# WS-Man Client

A pure Java WS-Man client implemented using JAX-WS & CXF with support for:
* Enumerate and Pull Operations (DSP8037)
* Get Operations (DSP8035)
* Identify Operation (DSP0226)
* Basic, NTLM and SPNEGO Authentication
* OSGi Compatible

## Java Example

Artifacts are available in Maven Central. Add it to your Maven project using:

```xml
<dependency>
  <groupId>org.opennms.core.wsman</groupId>
  <artifactId>org.opennms.core.wsman.cxf</artifactId>
  <version>1.1.1</version>
</dependency>
```

And start enumerating resources:

```java
package org.opennms.core.wsman.example;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import org.opennms.core.wsman.WSManClient;
import org.opennms.core.wsman.WSManConstants;
import org.opennms.core.wsman.WSManEndpoint;
import org.opennms.core.wsman.WSManVersion;
import org.opennms.core.wsman.cxf.CXFWSManClientFactory;
import org.w3c.dom.Node;

public class WSManClientExample {

    public static void main(String[] args) throws MalformedURLException {
        WSManEndpoint endpoint = new WSManEndpoint.Builder("https://127.0.0.1/wsman")
                .withServerVersion(WSManVersion.WSMAN_1_0)
                .withStrictSSL(false)
                .build();
        WSManClient client = new CXFWSManClientFactory().getClient(endpoint);

        List<Node> nodes = new ArrayList<>();
        client.enumerateAndPullUsingFilter(
                WSManConstants.CIM_ALL_AVAILABLE_CLASSES,
                WSManConstants.XML_NS_WQL_DIALECT,
                "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0",
                nodes,
                true);
    }
}
```

## Compiling From Source

Requires Java 8 and Maven 3 (tested with 3.3.3)

```sh
mvn clean package
```

## Using the CLI

Once you've successfully compiled the project, you'll want to locate the .jar artifact provided by the `cli` module:

```sh
export WSMAN_CLI_JAR=cli/target/org.opennms.core.wsman.cli-1.0-SNAPSHOT.jar
```

### Examples

#### Enumeration

Retrieving the computer system details from an iDrac 6 card

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin -resourceUri http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem
```

Retrieving all of the service details from a Windows 2008 Server:

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r http://win2k8:5985 -u Administrator -p PASsW0rdz -resourceUri http://schemas.microsoft.com/wbem/wsman/1/wmi/root/cimv2/Win32_Service
```

#### Enumeration with WQL filter

Retrieving details from the active power supply on an iDrac 6 card

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin "select DeviceDescription,PrimaryStatus,TotalOutputPower,InputVoltage,Range1MaxInputPower,FirmwareVersion,RedundancyStatus from DCIM_PowerSupplyView where DetailedState != 'Absent' and PrimaryStatus != 0"
```

#### Get with selectors

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin -o GET -resourceUri http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_ComputerSystem -s CreationClassName=DCIM_ComputerSystem -s Name=srv:system
```

#### Debugging:

Output the WS-Man requests and responses by setting the `-vvv` flag.

```sh
java -jar $WSMAN_CLI_JAR -w WSMAN_1_0 -r https://idrac/wsman -u root -p calvin -resourceUri http://schemas.dell.com/wbem/wscim/1/cim-schema/2/DCIM_PowerSupplyView -v TRACE -vvv
```
