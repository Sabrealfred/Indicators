# Reads versionCode/versionName straight out of an APK's binary AndroidManifest.xml.
#
# The point is to check the number the release marker DECLARES against the number the APK
# actually carries, because that is the pair UpdateService compares before it hands anything to
# the installer — a mismatch there is a rejected update, and it would be silent from the outside.
import struct, zipfile, sys

RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102

raw = zipfile.ZipFile(sys.argv[1]).read("AndroidManifest.xml")

def strings(buf, off):
    _t, hsize, _size = struct.unpack_from("<HHI", buf, off)
    count, _style_count, _flags, strings_start, _styles_start = struct.unpack_from("<IIIII", buf, off + 8)
    utf8 = bool(_flags & (1 << 8))
    offsets = struct.unpack_from(f"<{count}I", buf, off + hsize)
    out = []
    for o in offsets:
        p = off + strings_start + o
        if utf8:
            n = buf[p]
            if n & 0x80: n = ((n & 0x7F) << 8) | buf[p + 1]; p += 2
            else: p += 1
            m = buf[p]
            if m & 0x80: m = ((m & 0x7F) << 8) | buf[p + 1]; p += 2
            else: p += 1
            out.append(buf[p:p + m].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", buf, p)[0]
            out.append(buf[p + 2:p + 2 + n * 2].decode("utf-16-le", "replace"))
    return out

# chunk 0 is the file header; the first inner chunk is the string pool
pool_off = 8
assert struct.unpack_from("<H", raw, pool_off)[0] == RES_STRING_POOL
pool = strings(raw, pool_off)

off = 8
while off < len(raw):
    ctype, hsize, csize = struct.unpack_from("<HHI", raw, off)
    if ctype == RES_XML_START_ELEMENT:
        name_idx = struct.unpack_from("<I", raw, off + 20)[0]
        if pool[name_idx] == "manifest":
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", raw, off + 24)
            base = off + 16 + attr_start
            for i in range(attr_count):
                a = base + i * attr_size
                _ns, aname, arawval = struct.unpack_from("<III", raw, a)
                _sz, _r0, dtype, data = struct.unpack_from("<HBBI", raw, a + 12)
                label = pool[aname]
                if label in ("versionCode", "versionName", "compileSdkVersion"):
                    value = pool[arawval] if arawval != 0xFFFFFFFF else data
                    print(f"{label} = {value}")
            break
    off += csize
