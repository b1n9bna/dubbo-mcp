#!/usr/bin/env python3
"""Check the distributed fat JAR, including boundaries Maven's graph cannot prove."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
properties = ET.parse(ROOT / "pom.xml").find("m:properties", NS)
versions = {node.tag.rsplit("}", 1)[-1]: node.text for node in properties}


def check(path):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        classes = [name for name in names if name.endswith(".class")]
        assert len(classes) == len(set(classes)), "Duplicate class entries in fat JAR"
        assert not any(name.startswith("com/fasterxml/jackson/") for name in names), "Jackson was reintroduced"
        assert not any(name.startswith(("ch/qos/logback/", "org/apache/log4j/")) for name in names), "Extra logging implementation"
        assert not any(name.startswith(("okhttp3/tls/", "com/codahale/metrics/", "org/xerial/snappy/")) for name in names), "Test-only server dependencies shipped"
        binding = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
        providers = [line.strip() for line in jar.read(binding).decode().splitlines()
                     if line.strip() and not line.lstrip().startswith("#")]
        assert providers == ["org.slf4j.simple.SimpleServiceProvider"], providers
        # OkHttp's Gradle-built JAR has no Maven pom.properties; inspect its actual
        # compiled user-agent constant instead of trusting the requested POM version.
        okhttp = ("okhttp/" + versions["okhttp.version"]).encode()
        assert okhttp in jar.read("okhttp3/internal/Util.class"), "Unexpected OkHttp binary"
        expected = {
            "org.apache.zookeeper/zookeeper": versions["zookeeper.version"],
            "org.slf4j/slf4j-api": versions["slf4j.version"],
            "org.slf4j/slf4j-simple": versions["slf4j.version"],
        }
        netty = [name for name in names if name.startswith("META-INF/maven/io.netty/") and name.endswith("/pom.properties")]
        assert netty, "Netty metadata missing"
        for name in netty:
            expected[name[len("META-INF/maven/"):-len("/pom.properties")]] = versions["netty.version"]
        for artifact, version in expected.items():
            metadata = jar.read("META-INF/maven/" + artifact + "/pom.properties").decode()
            assert "version=" + version in metadata.splitlines(), (artifact, metadata)
        for name in classes:
            if name.startswith("META-INF/versions/") or name.endswith("module-info.class"):
                continue
            major = int.from_bytes(jar.read(name)[6:8], "big")
            assert major <= 52, ("Requires newer than Java 8", name, major)
    print("Packaged dependencies: Jackson absent, one logging provider, aligned Netty, Java 8 bytecode: PASS")


if __name__ == "__main__":
    check(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "target/dubbo-mcp.jar")
