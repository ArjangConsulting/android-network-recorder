# Contributing

## Development Approaches

### Standalone Development

Clone this repo directly and work on it independently:

```bash
git clone git@github.com:ArjangConsulting/android-network-recorder.git
cd android-network-recorder
gradle :api-trace-core:assemble :api-trace-debug:assemble :api-trace-noop:assemble
```

### Container Repo Development

This repo is also used as a submodule in the [mobile-network-recorder](https://github.com/ArjangConsulting/mobile-network-recorder) container repo for cross-platform development:

```bash
git clone --recurse-submodules git@github.com:ArjangConsulting/mobile-network-recorder.git
cd mobile-network-recorder/android
```

When developing via the container repo:

1. Make changes inside the `android/` submodule directory.
2. Commit and push changes in the submodule first.
3. Then update the submodule pointer in the container repo with a separate commit.

### Validation

Run `gradle :api-trace-core:assemble :api-trace-debug:assemble :api-trace-noop:assemble` if Gradle is available. Report environment blockers explicitly.

### Cross-Platform Considerations

This SDK has an iOS counterpart at [ios-network-recorder](https://github.com/ArjangConsulting/ios-network-recorder). If your changes affect:

- Capture semantics or lifecycle
- Redaction behavior
- Exported record format or JSON keys
- Bootstrap/install behavior

...consider whether the iOS SDK needs a corresponding update.
