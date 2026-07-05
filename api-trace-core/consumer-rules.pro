# No keep rules required: the SDK uses no reflection-based serialization, and R8
# retains public API reachable from consumer call sites. Avoid -keep rules here so
# class names are obfuscated normally in consumer release builds.
