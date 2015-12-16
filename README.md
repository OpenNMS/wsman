# WS-Man Client

A pure Java WS-Man client implemented using JAX-WS & CXF.

## Compiling

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
