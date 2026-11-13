# Contributing

Bug reports and pull requests are welcome.

- **Bugs:** open an issue with your device, Android version and the steps. Do not attach private
  documents; a small made-up PDF that shows the problem is ideal.
- **Security problems:** see [SECURITY.md](SECURITY.md); please do not open a public issue.
- **Changes:** run the tests listed in the README before opening a pull request. Changes to saving,
  storage or the engine boundary need a test.

Three rules are not negotiable, because they are what the app promises:

1. No network permission, no analytics, no advertising.
2. No new permission and no new exported component. The manifest check enforces this.
3. Nothing is logged that could identify a document: no file names, paths, passwords or content.

Contributions are accepted under the Apache License 2.0, the licence of the project.
