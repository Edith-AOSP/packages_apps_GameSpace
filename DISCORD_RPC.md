# Discord application setup

GameSpace publishes Rich Presence with the official Discord Social SDK. The
SDK requires a Discord application ID; it does not use a bot token or user
token.

## Create the application

1. Open <https://discord.com/developers/applications>.
2. Create an application, or select the existing GameSpace application.
3. Open **Discord Social SDK → Getting Started**, complete the form, and submit
   it. The SDK must be enabled for this application.
4. Copy the application ID from **General Information**.

## Set the ID

### Edith / prebuilt system image

Set the ID in the static resource overlay:

```text
vendor/edithui/packages/GameSpaceRpcOverlay/res/values/config.xml
```

```xml
<string name="discord_application_id" translatable="false">123456789012345678</string>
```

The GameSpace resource stays empty; the overlay supplies the value.

### Standalone GameSpace build

Set the resource directly:

```text
app/src/main/res/values/discord.xml
```

```xml
<string name="discord_application_id" translatable="false">123456789012345678</string>
```

## Build and verify

```bash
m GameSpaceRpcOverlay GameSpace
```

On a device:

```bash
adb shell cmd overlay list | grep -i gamespace
```

The overlay must be listed and enabled. Install Discord, sign in, enable
**GameSpace → Discord Rich Presence**, and launch a game.

If the ID is empty, the toggle is disabled. No presence is published.
