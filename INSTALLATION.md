# Installation

1. Use **Paper 1.21.4** and Java 21.
2. Push this folder to a GitHub repository.
3. Open **Actions → Build Zyphix** and run the workflow (or push a commit).
4. Download the `Zyphix-Plugin` artifact and put `zyphix-1.0.0.jar` in the server's `plugins` folder.
5. Restart the server.
6. Upload `resourcepack/` as a ZIP to a direct-download host. Configure the URL and SHA-1 in `server.properties` as `resource-pack=` and `resource-pack-sha1=`. Restart the server and accept the pack on the client.

Do not install this on a live server before testing on a backup. The resource pack is required for the Zyphix texture and 3D model.
