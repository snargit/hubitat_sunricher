/*
 * Sunricher SR-ZV9001T-RGBW (SRZV9001TRGBWWH) - Hubitat Driver
 *
 * Features:
 *  - Central Scene Notification -> Button events
 *  - CentralSceneSupportedReport -> sets numberOfButtons
 *  - SceneActivationSet (0x2B) -> button pushed mapping (supports Sunricher S1..S4 = 0x10/0x20/0x30/0x40)
 *  - Optional association of Group 2 to Hub and/or other node IDs
 *  - Node ID validation supports mesh + Z-Wave Long Range (default 1..4000)
 *  - Proper sendHubCommand usage via HubMultiAction (no sendHubCommand(List, delay))
 */

import groovy.transform.Field
import hubitat.zwave.Command
import hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation
import hubitat.zwave.commands.security2v1.Security2MessageEncapsulation
import hubitat.zwave.commands.supervisionv1.SupervisionGet
import hubitat.zwave.commands.centralscenev3.CentralSceneNotification
import hubitat.zwave.commands.centralscenev3.CentralSceneSupportedReport
import hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelSet
import hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelStartLevelChange
import hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelStopLevelChange
import hubitat.zwave.commands.switchcolorv3.SwitchColorSet
import hubitat.zwave.commands.switchcolorv3.SwitchColorStartLevelChange
import hubitat.zwave.commands.switchcolorv3.SwitchColorStopLevelChange
import hubitat.zwave.commands.basicv1.BasicSet
import hubitat.zwave.commands.associationv2.AssociationReport
import hubitat.zwave.commands.versionv2.VersionReport
import hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport

// Scene Activation (CC 0x2B)
import hubitat.zwave.commands.sceneactivationv1.SceneActivationSet

metadata {
    definition(
        name: "Sunricher SR-ZV9001T-RGBW Wall Panel",
        namespace: "copilot.sunricher",
        author: "Microsoft Copilot",
        importUrl: "https://raw.githubusercontent.com/snargit/hubitat_sunricher/refs/heads/main/SunRicher_SRZV9001TRGBW.groovy"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Configuration"
        capability "Refresh"
        capability "Initialize"

        capability "PushableButton"
        capability "HoldableButton"
        capability "ReleasableButton"
        capability "DoubleTapableButton"

        // Optional state attributes if associated to hub
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorControl"

        attribute "lastCentralScene", "string"
        attribute "lastZwave", "string"
        attribute "lastAssociationSet", "string"
        attribute "supportedScenes", "number"

        fingerprint mfr: "0330", prod: "0300", deviceId: "A108",
                inClusters: "5E,85,59,8E,55,86,72,5A,73,98,9F,6C,5B,7A",
                outClusters: "26,33,2B,2C",
                deviceJoinName: "Sunricher RGBW Wall Panel"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true

        input name: "associateGroup2ToHub", type: "bool",
                title: "Associate Group 2 to Hub (to receive brightness/color wheel/PWM/Scene Activation commands)",
                defaultValue: true

        input name: "assocGroup2NodeIds", type: "string",
                title: "Additional Association Group 2 Node IDs (comma/space-separated, supports LR)",
                description: "Examples: 5,12,18 or 260 261. Invalid IDs ignored. Default valid range 1..4000.",
                required: false

        input name: "maxNodeId", type: "number",
                title: "Max Node ID allowed (default 4000, for LR support)",
                description: "Set to 232 if you want to restrict validation to classic mesh-only IDs.",
                defaultValue: 4000

        input name: "createLevelColorEvents", type: "bool",
                title: "Create Switch/Level/Color events when Multilevel/Color commands are received",
                defaultValue: true

        input name: "commandDelayMs", type: "number",
                title: "Delay between Z-Wave commands sent by this driver (ms)",
                defaultValue: 100

        input name: "sceneActivationMapsToButtons", type: "bool",
                title: "Map SceneActivationSet sceneId (0x10/0x20/0x30/0x40 etc.) to button pushed events",
                defaultValue: true
    }
}

@Field static Map<Integer, Integer> CC_VERS = [
        0x5B: 3, // Central Scene
        0x26: 4, // Switch Multilevel
        0x33: 3, // Switch Color
        0x2B: 1, // Scene Activation
        0x85: 2, // Association
        0x8E: 3, // Multi Channel Association
        0x86: 2, // Version
        0x72: 2, // Manufacturer Specific
        0x5A: 1, // Device Reset Locally
        0x6C: 1, // Supervision
        0x98: 1, // Security (S0)
        0x9F: 1, // Security 2 (S2)
        0x7A: 3, // Firmware Update MD
        0x73: 1, // Powerlevel
        0x55: 1, // Transport Service
        0x59: 1, // Association Group Info
        0x5E: 2  // Z-Wave Plus Info
]

@Field static final Integer MIN_NODE_ID = 1
@Field static final Integer DEFAULT_MAX_NODE_ID = 4000

/* -------------------------- Lifecycle -------------------------- */

void installed() {
    if (txtEnable) log.info "${device.displayName} installed"
    state.rgb = [r:0, g:0, b:0, ww:0, cw:0]
    sendEvent(name: "numberOfButtons", value: 1, displayed: false)
    initialize()
}

void updated() {
    if (txtEnable) log.info "${device.displayName} updated"
    if (logEnable) runIn(1800, "logsOff")
}

void initialize() {
    if (txtEnable) log.info "${device.displayName} initialize()"
    configure()
}

void logsOff() {
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    log.warn "${device.displayName} debug logging disabled"
}

/* -------------------------- Sending -------------------------- */

/**
 * Hubitat drivers support sendHubCommand(HubAction) or sendHubCommand(HubMultiAction) only.
 * This wraps a List<String> of Z-Wave commands into HubMultiAction with optional delayBetween().
 */
private void sendToDevice(List<String> cmds, Long delayMs = null) {
    if (!cmds) return
    Long d = (delayMs != null) ? delayMs : ((settings?.commandDelayMs ?: 100) as Long)
    List<String> payload = delayBetween(cmds, d)   // built-in helper in Hubitat Groovy
    sendHubCommand(new hubitat.device.HubMultiAction(payload, hubitat.device.Protocol.ZWAVE))
}

private void sendToDevice(String cmd) {
    if (!cmd) return
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

/* -------------------------- Helpers -------------------------- */

private Integer effectiveMaxNodeId() {
    Integer m = null
    try {
        m = (settings?.maxNodeId != null) ? (settings.maxNodeId as Integer) : null
    } catch (ignored) { }
    if (m == null || m < 1) return DEFAULT_MAX_NODE_ID
    return m
}

private boolean isValidNodeId(Integer nodeId, Integer min = MIN_NODE_ID, Integer max = null) {
    Integer maxEff = (max != null) ? max : effectiveMaxNodeId()
    return (nodeId != null && nodeId >= min && nodeId <= maxEff)
}

private List<Integer> parseNodeIdList(String raw, Integer min = MIN_NODE_ID, Integer max = null) {
    List<Integer> out = []
    if (!raw) return out

    Integer maxEff = (max != null) ? max : effectiveMaxNodeId()

    raw.split(/[,\s]+/).each { token ->
        if (!token) return
        Integer n = null
        try {
            n = token.trim().toInteger()
        } catch (ignored) {
            if (logEnable) log.warn "${device.displayName}: ignoring non-numeric node id '${token}'"
            return
        }

        if (!isValidNodeId(n, min, maxEff)) {
            if (logEnable) log.warn "${device.displayName}: ignoring invalid node id ${n} (valid range ${min}..${maxEff})"
            return
        }

        if (!out.contains(n)) out << n
    }
    return out
}

/* -------------------------- Z-Wave Formatting -------------------------- */

String secureCmd(Command cmd) {
    if (isSecure()) {
        return zwaveSecureEncap(cmd)
    } else {
        return cmd.format()
    }
}

boolean isSecure() {
    def zwSec = device.getDataValue("zwaveSecurity")
    def spc  = device.getDataValue("zwaveSecurePairingComplete")
    return (spc == "true") || (zwSec && zwSec != "None")
}

/* -------------------------- Z-Wave Parsing -------------------------- */

void parse(String description) {
    if (description?.startsWith("Err")) {
        if (logEnable) log.warn "parse error: ${description}"
        return
    }

    Command cmd = zwave.parse(description, CC_VERS)
    if (cmd) {
        if (logEnable) log.debug "parse: ${cmd}"
        zwaveEvent(cmd)
    } else {
        if (logEnable) log.debug "parse: unable to parse: ${description}"
    }
}

/* -------------------------- Encapsulation -------------------------- */

void zwaveEvent(SecurityMessageEncapsulation cmd) {
    Command encap = cmd.encapsulatedCommand(CC_VERS)
    if (encap) {
        if (logEnable) log.debug "S0 encap -> ${encap}"
        zwaveEvent(encap)
    }
}

void zwaveEvent(Security2MessageEncapsulation cmd) {
    Command encap = cmd.encapsulatedCommand(CC_VERS)
    if (encap) {
        if (logEnable) log.debug "S2 encap -> ${encap}"
        zwaveEvent(encap)
    }
}

void zwaveEvent(SupervisionGet cmd) {
    Command encap = cmd.encapsulatedCommand(CC_VERS)
    if (encap) {
        if (logEnable) log.debug "SupervisionGet -> ${encap}"
        zwaveEvent(encap)
    }
    def rep = zwave.supervisionV1.supervisionReport(
            sessionID: cmd.sessionID,
            moreStatusUpdates: false,
            reserved: 0,
            status: 0xFF,
            duration: 0
    )
    sendToDevice(secureCmd(rep))
}

/* -------------------------- Central Scene Supported -------------------------- */

/**
 * When the hub requests centralSceneSupportedGet(), the device can reply with CentralSceneSupportedReport.
 * The common pattern is to use supportedScenes as the numberOfButtons. [1](https://github.com/jvmahon/HubitatDriverTools/blob/main/zwaveTools.centralSceneTools.groovy)
 */
void zwaveEvent(CentralSceneSupportedReport cmd) {
    if (logEnable) log.debug "CentralSceneSupportedReport: ${cmd}"

    Integer scenes = (cmd.supportedScenes ?: 0) as Integer
    if (scenes > 0) {
        sendEvent(name: "supportedScenes", value: scenes, displayed: false)
        sendEvent(name: "numberOfButtons", value: scenes, displayed: false)
        if (txtEnable) log.info "${device.displayName} reports supportedScenes=${scenes} (numberOfButtons updated)"
    } else {
        if (logEnable) log.warn "${device.displayName} CentralSceneSupportedReport had no supportedScenes value"
    }
}

/* -------------------------- Central Scene Notification -> Button events -------------------------- */

void zwaveEvent(CentralSceneNotification cmd) {
    int scene = cmd.sceneNumber as int
    int key   = cmd.keyAttributes as int

    String action = keyAttrToAction(key)

    Integer cur = (device.currentValue("numberOfButtons") ?: 1) as Integer
    if (scene > cur) sendEvent(name: "numberOfButtons", value: scene, displayed: false)

    String desc = "${device.displayName} scene ${scene} ${action} (keyAttr ${key})"
    sendEvent(name: "lastCentralScene", value: "scene=${scene}, action=${action}, keyAttr=${key}", displayed: false)

    if (txtEnable) log.info desc

    Map evt = [
            name: action,
            value: scene,
            isStateChange: true,
            descriptionText: desc,
            type: "physical"
    ]
    int taps = keyAttrToTapCount(key)
    if (taps > 2) evt.data = [tapCount: taps]

    sendEvent(evt)
}

String keyAttrToAction(int keyAttr) {
    switch(keyAttr) {
        case 0: return "pushed"
        case 1: return "released"
        case 2: return "held"
        case 3: return "doubleTapped"
        case 4: return "pushed"
        case 5: return "pushed"
        case 6: return "pushed"
        default: return "pushed"
    }
}

int keyAttrToTapCount(int keyAttr) {
    switch(keyAttr) {
        case 3: return 2
        case 4: return 3
        case 5: return 4
        case 6: return 5
        default: return 0
    }
}

/* -------------------------- Scene Activation Set (0x2B) -------------------------- */

/**
 * Sunricher manual: S1/S2/S3/S4 can send Scene Activation Set with Scene ID = 0x10/0x20/0x30/0x40
 * in direct control mode. [2](https://stevessmarthomeguide.com/z-wave-basics/)
 *
 * If this device is associated to the hub for the relevant group, the hub may receive these.
 * We translate them into pushed button events (button=sceneId/0x10 when evenly divisible).
 */
void zwaveEvent(SceneActivationSet cmd) {
    if (logEnable) log.debug "SceneActivationSet: ${cmd}"

    if (!settings.sceneActivationMapsToButtons) {
        sendEvent(name: "lastZwave", value: "SceneActivationSet(sceneId=${cmd.sceneId}, dimmingDuration=${cmd.dimmingDuration})", displayed: false)
        return
    }

    Integer sceneId = (cmd.sceneId ?: 0) as Integer
    Integer button = null

    // Sunricher mapping described: 0x10/0x20/0x30/0x40 for S1..S4 [2](https://stevessmarthomeguide.com/z-wave-basics/)
    if (sceneId > 0 && (sceneId % 0x10 == 0)) {
        Integer n = (sceneId / 0x10) as Integer
        if (n >= 1) button = n
    }

    if (button != null) {
        // Ensure numberOfButtons is at least this high
        Integer cur = (device.currentValue("numberOfButtons") ?: 1) as Integer
        if (button > cur) sendEvent(name: "numberOfButtons", value: button, displayed: false)

        String desc = "${device.displayName} SceneActivationSet sceneId=0x${Integer.toHexString(sceneId).toUpperCase()} -> pushed ${button}"
        if (txtEnable) log.info desc

        sendEvent(name: "pushed", value: button, isStateChange: true, type: "physical", descriptionText: desc)
        sendEvent(name: "lastZwave", value: "SceneActivationSet(sceneId=${sceneId}, button=${button})", displayed: false)
    } else {
        // If sceneId isn't a clean 0x10 multiple, still record it for troubleshooting
        if (logEnable) log.warn "${device.displayName}: SceneActivationSet sceneId=${sceneId} not mapped to a button"
        sendEvent(name: "lastZwave", value: "SceneActivationSet(sceneId=${sceneId}, unmapped)", displayed: false)
    }
}

// --- Switch capability commands (required when capability "Switch" is declared) ---

void on() {
    if (logEnable) log.debug "${device.displayName} on()"
    // This device is a controller; turning it "on" is typically virtual/stateful only
    sendEvent(name: "switch", value: "on", isStateChange: true,
              descriptionText: "${device.displayName} switch is on")
}

void off() {
    if (logEnable) log.debug "${device.displayName} off()"
    // This device is a controller; turning it "off" is typically virtual/stateful only
    sendEvent(name: "switch", value: "off", isStateChange: true,
              descriptionText: "${device.displayName} switch is off")
}

/* -------------------------- Direct-control commands (optional) -------------------------- */

void zwaveEvent(SwitchMultilevelSet cmd) {
    if (!createLevelColorEvents) return

    Integer level = zwToPercent(cmd.value as int)
    if (cmd.value == 0) {
        sendEvent(name: "switch", value: "off", type: "physical")
        sendEvent(name: "level", value: 0, unit: "%", type: "physical")
    } else {
        sendEvent(name: "switch", value: "on", type: "physical")
        sendEvent(name: "level", value: level, unit: "%", type: "physical")
    }

    if (txtEnable) log.info "${device.displayName} level set -> ${level}%"
}

void zwaveEvent(SwitchMultilevelStartLevelChange cmd) {
    if (logEnable) log.debug "StartLevelChange: ${cmd}"
}

void zwaveEvent(SwitchMultilevelStopLevelChange cmd) {
    if (logEnable) log.debug "StopLevelChange: ${cmd}"
}

void zwaveEvent(BasicSet cmd) {
    if (!createLevelColorEvents) return
    String sw = (cmd.value as int) == 0 ? "off" : "on"
    sendEvent(name: "switch", value: sw, type: "physical")
    if (txtEnable) log.info "${device.displayName} basic -> ${sw}"
}

void zwaveEvent(SwitchColorSet cmd) {
    if (!createLevelColorEvents) return

    Map<Integer,Integer> comps = [:]
    try {
        cmd.colorComponent?.each { cc ->
            Integer id = (cc.colorComponentId ?: cc.colorComponent) as Integer
            Integer v  = (cc.value ?: cc.colorComponentValue) as Integer
            comps[id] = v
        }
    } catch (e) {
        if (logEnable) log.warn "SwitchColorSet parse fallback: ${e}"
    }

    if (comps.containsKey(2)) state.rgb.r = comps[2]
    if (comps.containsKey(3)) state.rgb.g = comps[3]
    if (comps.containsKey(4)) state.rgb.b = comps[4]
    if (comps.containsKey(0)) state.rgb.ww = comps[0]
    if (comps.containsKey(1)) state.rgb.cw = comps[1]

    applyColorEventsFromState()

    if (txtEnable) log.info "${device.displayName} color set -> r${state.rgb.r} g${state.rgb.g} b${state.rgb.b} ww${state.rgb.ww} cw${state.rgb.cw}"
}

void zwaveEvent(SwitchColorStartLevelChange cmd) {
    if (logEnable) log.debug "ColorStartLevelChange: ${cmd}"
}

void zwaveEvent(SwitchColorStopLevelChange cmd) {
    if (logEnable) log.debug "ColorStopLevelChange: ${cmd}"
}

private void applyColorEventsFromState() {
    int r = (state.rgb.r ?: 0) as int
    int g = (state.rgb.g ?: 0) as int
    int b = (state.rgb.b ?: 0) as int
    int ww = (state.rgb.ww ?: 0) as int
    int cw = (state.rgb.cw ?: 0) as int

    boolean anyOn = (r + g + b + ww + cw) > 0
    sendEvent(name: "switch", value: anyOn ? "on" : "off", type: "physical")

    if ((r + g + b) > 0) {
        Map hsv = rgbToHsv(r, g, b)
        sendEvent(name: "hue", value: hsv.h, unit: "%", type: "physical")
        sendEvent(name: "saturation", value: hsv.s, unit: "%", type: "physical")
        sendEvent(name: "level", value: hsv.v, unit: "%", type: "physical")
        String hex = String.format("#%02X%02X%02X", r, g, b)
        sendEvent(name: "color", value: hex, type: "physical")
        sendEvent(name: "colorMode", value: "RGB", displayed: false)
    } else {
        int wMax = Math.max(ww, cw)
        int lvl = Math.round((wMax / 255.0) * 100.0) as int
        sendEvent(name: "level", value: lvl, unit: "%", type: "physical")
        sendEvent(name: "colorMode", value: "W", displayed: false)
    }
}

private Map rgbToHsv(int r, int g, int b) {
    float rf = r / 255f
    float gf = g / 255f
    float bf = b / 255f
    float max = Math.max(rf, Math.max(gf, bf))
    float min = Math.min(rf, Math.min(gf, bf))
    float delta = max - min

    float h = 0
    if (delta != 0) {
        if (max == rf)      h = ((gf - bf) / delta) % 6f
        else if (max == gf) h = ((bf - rf) / delta) + 2f
        else                h = ((rf - gf) / delta) + 4f
        h *= 60f
        if (h < 0) h += 360f
    }
    float s = (max == 0) ? 0 : (delta / max)
    float v = max

    int huePct = Math.round((h / 360f) * 100f) as int
    int satPct = Math.round(s * 100f) as int
    int valPct = Math.round(v * 100f) as int
    return [h: huePct, s: satPct, v: valPct]
}

private Integer zwToPercent(int zwVal) {
    if (zwVal <= 0) return 0
    if (zwVal >= 0xFF) return 100
    return Math.min(100, Math.round((zwVal / 99.0) * 100.0) as int)
}

/* -------------------------- Reports / Misc -------------------------- */

void zwaveEvent(AssociationReport cmd) {
    if (logEnable) log.debug "AssociationReport: ${cmd}"
    sendEvent(name: "lastZwave", value: "AssociationReport(group=${cmd.groupingIdentifier}, nodes=${cmd.nodeId})", displayed: false)
}

void zwaveEvent(VersionReport cmd) {
    if (logEnable) log.debug "VersionReport: ${cmd}"
}

void zwaveEvent(ManufacturerSpecificReport cmd) {
    if (logEnable) log.debug "ManufacturerSpecificReport: ${cmd}"
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) log.debug "Unhandled Z-Wave: ${cmd}"
    sendEvent(name: "lastZwave", value: cmd.toString(), displayed: false)
}

/* -------------------------- Commands -------------------------- */

void configure() {
    if (txtEnable) log.info "${device.displayName} configure()"

    List<String> cmds = []
    Integer maxEff = effectiveMaxNodeId()

    // Group 1 (Lifeline) to hub
    if (isValidNodeId(zwaveHubNodeId as Integer, MIN_NODE_ID, maxEff)) {
        cmds << secureCmd(zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId as Integer]))
        cmds << secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 1))
    } else {
        log.warn "${device.displayName}: zwaveHubNodeId '${zwaveHubNodeId}' invalid (valid ${MIN_NODE_ID}..${maxEff}); skipping Group 1 associationSet"
    }

    // Group 2 targets (hub optional + user node IDs)
    List<Integer> group2Targets = []

    if (associateGroup2ToHub) {
        if (isValidNodeId(zwaveHubNodeId as Integer, MIN_NODE_ID, maxEff)) {
            group2Targets << (zwaveHubNodeId as Integer)
        } else {
            log.warn "${device.displayName}: zwaveHubNodeId '${zwaveHubNodeId}' invalid; not adding hub to Group 2 targets"
        }
    }

    group2Targets.addAll(parseNodeIdList(settings.assocGroup2NodeIds, MIN_NODE_ID, maxEff))
    group2Targets = group2Targets.unique()

    if (group2Targets) {
        cmds << secureCmd(zwave.associationV2.associationSet(groupingIdentifier: 2, nodeId: group2Targets))
        cmds << secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 2))
        sendEvent(name: "lastAssociationSet", value: "Group2=${group2Targets} (maxNodeId=${maxEff})", displayed: false)
        if (txtEnable) log.info "${device.displayName} Group 2 association targets: ${group2Targets}"
    } else {
        if (txtEnable) log.info "${device.displayName} Group 2 association not set (no valid targets)."
        sendEvent(name: "lastAssociationSet", value: "Group2=none (maxNodeId=${maxEff})", displayed: false)
    }

    // Useful info / capability discovery
    cmds << secureCmd(zwave.versionV2.versionGet())
    cmds << secureCmd(zwave.manufacturerSpecificV2.manufacturerSpecificGet())
    cmds << secureCmd(zwave.centralSceneV3.centralSceneSupportedGet())  // expect CentralSceneSupportedReport [1](https://github.com/jvmahon/HubitatDriverTools/blob/main/zwaveTools.centralSceneTools.groovy)

    sendToDevice(cmds, (settings?.commandDelayMs ?: 100) as Long)
}

void refresh() {
    if (txtEnable) log.info "${device.displayName} refresh()"
    List<String> cmds = []
    cmds << secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 1))
    cmds << secureCmd(zwave.associationV2.associationGet(groupingIdentifier: 2))
    cmds << secureCmd(zwave.centralSceneV3.centralSceneSupportedGet())
    cmds << secureCmd(zwave.versionV2.versionGet())
    sendToDevice(cmds, (settings?.commandDelayMs ?: 100) as Long)
}