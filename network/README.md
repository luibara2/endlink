# NetworkCompatible

## Introduction

You can join the [Discord](https://discord.gg/5z4GuSnqmQ) for help with this fork. This raknet portion of this library is a fork of [CloudburstMC/Network](https://github.com/CloudburstMC/Network) with a focus on improving the compatibility of the client side of the library to more closely align with the vanilla Minecraft Bedrock client.

The new package `netty-transport-nethernet` is also included, which provides support for the Nethernet protocol. This is achieved using a JNI wrapper for the native WebRTC library.

## Package Specific Information

See the respective README files for each transport library for more information:

- [netty-transport-raknet](transport-raknet/README.md)
- [netty-transport-nethernet](transport-nethernet/README.md)