# Pulls the signing certificate out of an APK's v2/v3 signing block.
#
# AGP does not write a v1 JAR signature for minSdk 24+, so there is no META-INF/*.RSA to read.
# The certificate lives in the APK Signing Block, which sits immediately before the ZIP central
# directory: [uint64 size][id-value pairs][uint64 size]["APK Sig Block 42"].
import struct, sys, subprocess, tempfile, os

path = sys.argv[1]
data = open(path, "rb").read()

# End of central directory, scanned backwards for its signature.
eocd = data.rfind(b"PK\x05\x06")
assert eocd > 0, "no EOCD"
cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]

MAGIC = b"APK Sig Block 42"
assert data[cd_offset - 16:cd_offset] == MAGIC, "no APK signing block (unsigned, or v1 only)"
block_size = struct.unpack_from("<Q", data, cd_offset - 24)[0]
block_start = cd_offset - block_size - 8
pairs = data[block_start + 8:cd_offset - 24]

found = {}
i = 0
while i < len(pairs):
    (plen,) = struct.unpack_from("<Q", pairs, i)
    (pid,) = struct.unpack_from("<I", pairs, i + 8)
    found[pid] = pairs[i + 12:i + 8 + plen]
    i += 8 + plen

names = {0x7109871a: "v2", 0xf05368c0: "v3", 0x1b93ad61: "v3.1", 0x42726577: "padding",
         0x2146444e: "source stamp", 0x6dff800d: "dependency info"}
print("blocks present:", ", ".join(sorted(names.get(k, hex(k)) for k in found)))

def u32(buf, off):
    return struct.unpack_from("<I", buf, off)[0]

def first_cert(scheme_value):
    # signers -> signer -> signed data -> digests, certificates
    signers = scheme_value[4:4 + u32(scheme_value, 0)]
    signer = signers[4:4 + u32(signers, 0)]
    signed = signer[4:4 + u32(signer, 0)]
    digests_len = u32(signed, 0)
    certs = signed[4 + digests_len:]
    certs_seq = certs[4:4 + u32(certs, 0)]
    return certs_seq[4:4 + u32(certs_seq, 0)]

# keytool reads a file rather than stdin, so the DER has to land somewhere — but it lands in a
# temporary directory that is cleaned up, not in whatever directory this was run from. The first
# version wrote cert-v2.der into the repository root and left it there.
with tempfile.TemporaryDirectory() as tmp:
    for pid in (0x7109871a, 0xf05368c0):
        if pid not in found:
            continue
        out = os.path.join(tmp, f"cert-{names[pid]}.der")
        with open(out, "wb") as handle:
            handle.write(first_cert(found[pid]))
        print(f"\n=== {names[pid]} signer certificate ===")
        r = subprocess.run(["keytool", "-printcert", "-file", out], capture_output=True, text=True)
        for line in r.stdout.splitlines():
            if any(k in line for k in ("Owner:", "SHA256:", "Valid from:", "Signature algorithm")):
                print(line.strip())
