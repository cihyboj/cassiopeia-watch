# Scratchpad Buffer Layout

## Overview
The scratchpad buffer uses a **fixed layout** where each client has a predetermined position in the buffer. This ensures data consistency across app restarts, regardless of client registration order.

## Buffer Layout

| Offset | Size | Client | Description |
|--------|------|--------|-------------|
| 0 | 3 bytes | `AlarmNameStorage` | Stores 6 alarm names using 3-bit encoding (18 bits total = 3 bytes) |
| **Total** | **3 bytes** | | |

> Offsets 3-4 were previously reserved for `ActionsStorage` (removed with the Actions
> feature). Treat offset 3 as the next available slot, but note that watches written by
> older builds may still carry stale bytes there.

## Architecture

### Fixed Offset Design
Each `ScratchpadClient` implementation declares its fixed position via `getStorageOffset()`:

```kotlin
interface ScratchpadClient {
    fun getStorageOffset(): Int  // Fixed position in buffer
    fun getStorageSize(): Int    // Number of bytes needed
    fun setBuffer(buffer: ByteArray)
    fun getBuffer(): ByteArray
}
```

### Benefits
1. **Order-independent**: Clients can register in any order
2. **Persistent-safe**: Buffer loaded after restart will map correctly to clients
3. **Predictable**: Easy to debug and verify buffer contents
4. **Extensible**: New clients can be added by declaring their offset

### Adding New Clients
To add a new client:

1. Determine the next available offset (currently 3)
2. Implement `ScratchpadClient` interface
3. Override `getStorageOffset()` to return your fixed offset
4. Override `getStorageSize()` to return your required bytes
5. Register with `ScratchpadManager` in your `init` block

Example:
```kotlin
@Singleton
class MyNewStorage @Inject constructor(
    private val manager: ScratchpadManager
) : ScratchpadClient {
    
    init {
        manager.register(this)
    }
    
    override fun getStorageOffset(): Int = 3  // Starts after AlarmNameStorage
    override fun getStorageSize(): Int = 4     // Needs 4 bytes
    
    // ... implement setBuffer() and getBuffer()
}
```

## Implementation Details

### AlarmNameStorage (Offset: 0, Size: 3 bytes)
- Stores 6 alarm names
- Each name encoded as 3-bit index (0-5 for names, 7 for "no name")
- Packing: 2 names per byte (lower 3 bits + upper 3 bits shifted by 4)
- Total: 6 names × 3 bits = 18 bits = 3 bytes

## Migration Notes
The previous implementation used dynamic layout based on registration order. This has been replaced with fixed offsets to ensure data integrity across app restarts.
