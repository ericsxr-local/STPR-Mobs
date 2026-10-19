# STPR-Mobs

[简体中文](README.zh-CN.md)

Creator: Eric_SXR

Custom mobs, spawn points, bosses, and tide events for STPR matches.

## Features

- Manage mob templates and spawn points with `/stprmob`.
- Configure map-specific behavior in YAML.

## Compatibility

- Plugin version: `1.2.6` (from `plugin.yml`).
- Target: Paper/Purpur 1.20.1, API `1.20`.
- Build JDK: 17.
- Required plugins: None declared.
- Optional plugins: None declared.

## Build and install

1. Run `mvn package` from this repository using JDK 17.
2. Copy the built JAR from `target/` to your server's `plugins/` directory.
3. Restart the server and edit the generated plugin configuration as needed.

## Commands and configuration

- Registered commands: `/stprmob`. See `src/main/resources/plugin.yml` for permissions and usage.
- Main configuration sections: `settings`, `messages`. See `src/main/resources/config.yml`.
