# Sunricher SR-ZV9001T-RGBW Driver Documentation

## Overview
The **Sunricher SR-ZV9001T-RGBW** is a versatile driver designed for RGBW lighting control, supporting various features to ensure optimal performance and user experience.

## Features
- Control of RGB and White lighting channels independently.
- Compatible with Zigbee networks.
- User-friendly configuration options.
- Automatic detection of supported command classes.

## Configuration
To configure the Sunricher SR-ZV9001T-RGBW driver:
1. Ensure that the driver is installed within your hub environment.
2. Access the configuration page of the driver to set parameters such as light intensity and color modes.
3. Use the configuration buttons to save settings.

## Button Mapping
| Button | Function | 
| ------ | -------- | 
| Button 1 | Toggle RGB on/off |
| Button 2 | Increase brightness |
| Button 3 | Decrease brightness |
| Button 4 | Change to Warm White |
| Button 5 | Change to Cool White |

## Supported Command Classes
The driver supports the following command classes:
- Basic Command Class
- Switch Binary Command Class
- Color Control Command Class
- Manufacturer Specific Command Class

## Troubleshooting
- If the driver does not respond to commands:
  - Check if the device is powered on and connected to the Zigbee network.
  - Restart the hub and the device to reset connections.
- For color issues:
  - Ensure proper configuration settings are applied for color control.

## Usage Examples
To turn on the RGBW lights:
```groovy
sendEvent(name: "switch", value: "on")
```

To set the color to red:
```groovy
sendEvent(name: "hue", value: 0)
sendEvent(name: "saturation", value: 100)
```

To adjust brightness:
```groovy
sendEvent(name: "level", value: 75)
```

## Conclusion
The Sunricher SR-ZV9001T-RGBW driver offers comprehensive functionality for managing RGBW lighting within your smart home environment. Follow the above instructions for proper configuration and troubleshooting.
