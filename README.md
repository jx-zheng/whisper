# whisper
Secure steganographic communication made easy

![Person writing with fountain pen](https://images.unsplash.com/photo-1455390582262-044cdead277a?ixlib=rb-4.0.3&ixid=MnwxMjA3fDB8MHxwaG90by1wYWdlfHx8fGVufDB8fHx8&auto=format&fit=crop&w=300&q=80)

**whisper** is a Java command line tool that allows you to embed [steganographic](https://en.wikipedia.org/wiki/Steganography) messages in RGB images via stenographic techniques.
The technique used in whisper is based on research by Hong-Juan Zhang and Hong-Jun Tang, as outlined in their paper, [A Novel Image Steganography Algorithm Against Statistical Analysis](https://ieeexplore.ieee.org/document/4370824).

## Building

`mvn clean package` builds an executable JAR in the `target/` directory.

## Running

`java -jar target/whisper-VERSION.jar` starts the tool. Use the `-h` flag to show usage help.

## Upcoming Features

- More encryption ciphers supported (Blowfish, Triple DES)
- Additional steganography schemes supported
- Ability to embed images as secret payload
- Ability to embed messages in other forms of media, including videos and audio
