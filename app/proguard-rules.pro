# Manifest entry points and Android callbacks are retained by Android defaults.
# The published AIDL AAR supplies consumer rules for its cross-process contract.
# Protocol frames use explicit binary encoding, so no reflection-based model keeps are needed.
-keepattributes SourceFile,LineNumberTable
