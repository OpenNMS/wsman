## 🚀 Releasing

Use the following commands to stage and publish a new release.  
**Example version: `1.3.4`**

---
### Automated Script

```bash
./tools/release.sh 1.3.4
```

---
### **1. Set the release version**

```bash
mvn versions:set -DnewVersion=1.3.4
mvn versions:commit
```

---

### **2. Commit and tag the release**

```bash
git commit -am "Release 1.3.4"
git tag v1.3.4
```

---

### **3. Publish to Maven Central (Central Publishing)**

```bash
mvn -Prelease deploy
```

---

### **4. Push commits and tags**

```bash
git push
git push --tags
```

---

### **5. Bump to the next snapshot version**

```bash
mvn versions:set -DnewVersion=1.3.5-SNAPSHOT
mvn versions:commit
git commit -am "Start 1.3.5-SNAPSHOT"
git push
```
