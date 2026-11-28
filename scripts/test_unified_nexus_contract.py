#!/usr/bin/env python3
"""Contract: Nexus has exactly one owner-facing app and one integrated shell.

Checks the production manifest and Kotlin entrypoints; CI Android compilation
and on-device acceptance remain separate mandatory gates.
"""
import pathlib
import json
import re
import sqlite3
import xml.etree.ElementTree as ET

ROOT=pathlib.Path(__file__).resolve().parents[1]
APP=ROOT/"app/src/main"
ANDROID="{http://schemas.android.com/apk/res/android}"
def attr(element,key):
    return element.get(ANDROID+key)
def check():
    manifest=ET.parse(APP/"AndroidManifest.xml").getroot()
    application=manifest.find("application")
    assert application is not None, "Missing application"
    launchers=[]
    for node in application.findall("activity")+application.findall("activity-alias"):
        for filt in node.findall("intent-filter"):
            actions={attr(a,"name") for a in filt.findall("action")}
            categories={attr(c,"name") for c in filt.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                launchers.append(attr(node,"name"))
    assert launchers==["com.asterion.nexus.NexusPremiumHomeActivity"], launchers
    assert len(application.findall("activity"))>1, "Native readers/workers must stay registered"
    shell=(APP/"java/com/asterion/nexus/NexusPremiumHomeActivity.kt").read_text(encoding="utf-8")
    workspace=(APP/"java/com/asterion/nexus/NexusUnifiedWorkspace.kt").read_text(encoding="utf-8")
    assert 'val destinations=listOf("Library","Organising","Downloading")' in shell, "Exactly three permanent destinations required"
    assert 'activeSection by mutableStateOf("Cloud")' in shell, "Library must start with TeraBox"
    assert 'detectHorizontalDragGestures' in shell, "Missing contextual swipe navigation"
    assert all(x in shell for x in ['"Gallery"','"Books"','"Videos"','"Assets/Files"','"Activity"','"Taxonomy"','"Review"','"Search"','"Transfers"'])
    assert 'NexusWorkspace(section=section' in shell, "Section routing must stay in the same Activity"
    assert "startActivity(Intent(this,NexusSearchActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCloudActivity::class.java))" not in shell
    assert "startActivity(Intent(this,NexusCoreActivity::class.java))" not in shell
    for route in ["NexusUnifiedSearch(", "NexusUnifiedTransfers(", "NexusUnifiedCloud(", "NexusCorePane("]:
        assert route in workspace, "Missing unified view "+route
    gradle=(ROOT/"app/build.gradle.kts").read_text(encoding="utf-8")
    assert 'applicationId = "com.asterion.nexus"' in gradle
    assert 'implementation(project(":fusion"))' in gradle
    assert 'implementation(project(":rounder"))' in gradle
    assert 'onQueryChanged(value)' in shell
    assert "FgTeraboxDocumentsProvider.PAGE_OFFSET" in workspace
    visual=(APP/"java/com/asterion/nexus/NexusVisual.kt").read_text(encoding="utf-8")
    icon=(APP/"res/drawable/ic_asterion_nexus.xml").read_text(encoding="utf-8")
    assert "0xFFD4D9E1" in visual, "Owner silver color is not installed"
    assert "0xFF0E1013" in visual, "Charcoal canvas is not installed"
    assert '#D4D9E1' in icon and '#D4B47D' not in icon, "Launcher icon conflicts with approved silver palette"
    assert 'aspectRatio(ratio)' in shell and 'contentScale=ContentScale.Fit' in shell, "Library must use native-ratio preview images"
    assert 'maxWidth>=680.dp->3' in shell and 'else->2' in shell, "Image-first mobile gallery width not preserved"
    assert "GoldColor" not in shell and "C_GOLD" not in workspace
    assert "NexusVisual.silver" in workspace and "NexusVisual.silver" in shell
    assert 'NexusUnifiedTransfers(' in workspace, "Native transfer queue must remain in the single workspace"
    assert 'span={item->' in shell and 'GridItemSpan(maxLineSpan)' in shell, "Featured original gallery tile missing"
    assert 'destinations.forEach' in shell, "Permanent navigation must be generated from one registry"
    assert 'select(name,when(name)' in shell and 'onRoute("Cloud")' in shell, "Source workflows must remain reachable"
    assert 'val ratio=thumbnail?.let{' in shell, "Media tiles must preserve original aspect ratio"
    registry=(APP/"java/com/asterion/nexus/NexusRegistry.java").read_text(encoding="utf-8")
    assert 'searchPage(String query,int offset,int limit,String category)' in registry
    assert 'val chosenCategory=category' in shell and 'loadPage(reset=true)' in shell
    assert '"Audio"->m.startsWith("audio/")' in shell
    assert 'case "Assets":' in registry and '"Assets/Files"->"Assets"' in shell
    assert 'HorizontalPager(state=pager' in shell and 'PdfRenderer(fd)' in shell and 'ZipFile(file)' in shell
    pane=(ROOT/"fusion/src/main/java/com/ailm/android/ui/screens/NexusCorePane.kt").read_text(encoding="utf-8")
    assert '"Review"->AppDestination.ReviewQueue' in pane
    assert '"Taxonomy"->AppDestination.Tags' in pane
    assert '"CoreGallery"->AppDestination.LibraryBrowser' in pane
    assert 'ScreenScaffold(destination=screen' in pane
    assert 'AppNavHost(' not in pane
    assert 'NexusCorePane(section,onCoreRoute)' in workspace
    core=(ROOT/"fusion/src/main/java/com/ailm/android/ui/screens/Screens.kt").read_text(encoding="utf-8")
    assert '(!queueLoaded || queueLoading || queueError!=null)' in core
    source=ROOT/"fusion/src/main/assets/nexus_reference_knowledge/series_1623_entries.json"
    records=json.loads(source.read_text(encoding="utf-8"))
    assert len(records)==1623
    assert len({x["series_code"] for x in records})==1623
    assert all(x["canonical_name"] and isinstance(x["aliases"],list) for x in records)
    runtime=(ROOT/"fusion/src/main/java/com/ailm/android/runtime/StandaloneRuntime.kt").read_text(encoding="utf-8")
    assert "seedBundledReferenceKnowledge()" in runtime
    assert "owner_taxonomy_20261128" in runtime and "parsed.tags.size == 1278" in runtime
    owner_taxonomy_regression()
    viewer=(APP/"java/com/asterion/nexus/NexusImageViewerActivity.kt").read_text(encoding="utf-8")
    router=(APP/"java/com/asterion/nexus/NexusFileOpen.java").read_text(encoding="utf-8")
    assert 'NexusImageViewerActivity.class' in router, "Pictures must open inside Nexus"
    assert 'NexusImageViewerActivity' in [attr(x,"name").split(".")[-1] for x in application.findall("activity")], "Integrated viewer not registered"
    assert 'boundedImage(source)' in viewer and 'rememberTransformableState' in viewer, "Safe integrated image viewer missing"
    assert 'case "Books":' in registry and 'case "Other":' in registry
    assert "VerticalPager(state=pager" in shell and "setVolume(0f,0f)" in shell
    assert "!pager.isScrollInProgress" in shell and "previewEnabled=false" in shell
    assert "nexus_position_ms" in router and "openAt(" in router
    assert "NexusCloudContentTile(" in workspace and "cloud.openThumbnail(entry.path,folder,page)" in workspace
    assert "accountScope==cloud.metadataScope()" in workspace
    assert "NexusFileDetails.show(" in viewer and "file_ratings" in registry
    registry_migration_regression(registry)
    assert "score BETWEEN 0 AND 100" in registry and "if(score==-1)" in registry
    taxonomy_revision_regression()
    cloud_account_regression()
    assembly=(APP/"java/com/asterion/nexus/NexusEpisodeAssembly.java").read_text(encoding="utf-8")
    assert 'NexusEpisodeAssemblyPane(onLibraryRefresh)' in workspace
    assert 'MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4' in assembly and 'evidence.verify(actual)' in assembly
    assert 'NexusOriginalStore.matches(original,item.length,item.sha256)' in assembly
    print("PASS: one Nexus launcher; approved navigation; native transfer routes; real cover flow; taxonomy revision SQL fixture")

def registry_migration_regression(registry):
    # Execute production registry DDL, including direct upgrade from v1.
    def ddl(method):
        start = registry.index(method)
        end = registry.index("\n        }", start)
        return re.findall(r'db\.execSQL\("([^"\n]+)"\)', registry[start:end])
    assert 'if(old==1)createFileRatings(db)' in registry
    assert 'if(old<3)createRemoteLocations(db)' in registry
    assert 'if(old<4)createCollections(db)' in registry
    assert 'createEvictions(db);createPlayback(db);createBookmarks(db);createCloudProofs(db);' in registry
    sha = "a" * 64
    with sqlite3.connect(":memory:") as db:
        db.execute("PRAGMA foreign_keys=ON")
        for sql in ddl("void onCreate("):
            db.execute(sql)
        db.execute("INSERT INTO originals VALUES(?,?,?,?)", (sha, 12, "video/mp4", 1))
        db.execute("INSERT INTO locations(sha256,source_kind,account_key,origin_key,display_name,created_at) VALUES(?,?,?,?,?,?)", (sha,"local","device","origin","Original",1))
        methods = ["createFileRatings","createRemoteLocations","createCollections","createEvictions","createPlayback","createBookmarks","createCloudProofs"]
        for method in methods:
            for sql in ddl("void "+method+"("):
                db.execute(sql)
        db.execute("INSERT INTO file_ratings VALUES(?,?,?)",(sha,87,1))
        for method in methods:
            for sql in ddl("void "+method+"("):
                db.execute(sql)
        assert db.execute("SELECT score FROM file_ratings WHERE sha256=?",(sha,)).fetchone()==(87,)
        assert db.execute("SELECT display_name FROM locations").fetchall()==[("Original",)]
        assert db.execute("PRAGMA foreign_key_check").fetchall()==[]
        actual={row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert {"file_ratings","remote_locations","collection_members","local_evictions","playback_progress","reader_bookmarks","remote_content_proofs"} <= actual
    print("PASS: additive registry v1-to-v8 schema, preserved original and rating")

def taxonomy_revision_regression():
    # Execute exact SQL from the Kotlin schema using SQLite. This is a
    # source/SQL regression, not an Android SQLiteOpenHelper migration test.
    schema=(ROOT/"fusion/src/main/java/com/ailm/android/runtime/FusionDatabaseSchema.kt").read_text(encoding="utf-8")
    ensure=schema.split("fun ensureArtifacts(",1)[1].split("private fun createVisualLearningTables",1)[0]
    assert "createTaxonomyRevision(db)" in ensure, "Legacy database repair must create taxonomy revision artifacts"
    body=schema.split("private fun createTaxonomyRevision(",1)[1].split("private fun createCharacterSheetTable(",1)[0]
    statements=re.findall(r'db\.execSQL\("([^"\n]+)"\)',body)
    trigger=re.search(r'db\.execSQL\("""(CREATE TRIGGER.*?)"""\)',body,re.S)
    assert len(statements)==2 and trigger
    with sqlite3.connect(":memory:") as db:
        db.execute("CREATE TABLE fusion_tags(id TEXT PRIMARY KEY)")
        db.execute("CREATE TABLE fusion_tag_aliases(id TEXT PRIMARY KEY)")
        db.execute("CREATE TABLE images(image_id INTEGER PRIMARY KEY, rating INTEGER)")
        db.execute("INSERT INTO images VALUES(1,87)")
        db.execute("INSERT INTO fusion_tags VALUES('existing')")
        def repair():
            for sql in statements:
                db.execute(sql)
            for table in ("fusion_tags","fusion_tag_aliases"):
                for operation in ("INSERT","UPDATE","DELETE"):
                    sql=trigger.group(1).replace("${table}",table).replace("$table",table).replace("$operation",operation)
                    db.execute(sql)
        repair()
        assert db.execute("SELECT revision FROM fusion_taxonomy_revision WHERE id=1").fetchone()==(0,)
        db.execute("INSERT INTO fusion_tags VALUES('new')")
        db.execute("UPDATE fusion_tags SET id='updated' WHERE id='new'")
        db.execute("DELETE FROM fusion_tags WHERE id='updated'")
        db.execute("INSERT INTO fusion_tag_aliases VALUES('alias')")
        repair()
        assert db.execute("SELECT revision FROM fusion_taxonomy_revision WHERE id=1").fetchone()==(4,)
        assert db.execute("SELECT rating FROM images WHERE image_id=1").fetchone()==(87,)
        assert db.execute("SELECT id FROM fusion_tags").fetchall()==[("existing",)]
        assert db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'").fetchone()==(6,)

def cloud_account_regression():
    # SQLite fixture exercises the actual new DDL and legacy migration shape.
    donor=APP/"java/com/azrael/pixivdumpsync"
    for filename,table,pk,legacy_pk,values in (
        ("TeraBoxRemoteOriginals.kt","remote_originals","account_scope,id","id",
         {"id":"same","url_cipher":"encrypted","category":"book","filename":"book.epub",
          "destination":"/Books","created_at":1,"updated_at":1,"state":"VERIFIED","remote_path":"/Books/book.epub"}),
        ("NduCloudQueue.kt","jobs","category,content_uri,account_scope","category,content_uri",
         {"category":"book","name":"book.epub","content_uri":"content://local/book","state":"VERIFIED",
          "remote_path":"/Books/book.epub","remote_size":12}),
    ):
        source=(donor/filename).read_text(encoding="utf-8")
        ddl=re.search(r'db\.execSQL\("""(CREATE TABLE '+table+r'\(.*?)"""\)',source,re.S).group(1)
        legacy=re.sub(r"\s*account_scope TEXT NOT NULL DEFAULT '',","",ddl).replace("PRIMARY KEY("+pk+")","PRIMARY KEY("+legacy_pk+")")
        with sqlite3.connect(":memory:") as db:
            db.execute(legacy)
            fields=",".join(values)
            placeholders=",".join("?" for _ in values)
            db.execute(f"INSERT INTO {table}({fields}) VALUES({placeholders})",tuple(values.values()))
            db.execute(f"ALTER TABLE {table} RENAME TO {table}_legacy")
            db.execute(ddl)
            db.execute(f"INSERT INTO {table}({fields}) SELECT {fields} FROM {table}_legacy")
            db.execute(f"DROP TABLE {table}_legacy")
            assert db.execute(f"SELECT account_scope,remote_path FROM {table}").fetchall()==[("","/Books/book.epub")]
            for scope in ("account-a","account-b"):
                db.execute(f"INSERT INTO {table}({fields},account_scope) VALUES({placeholders},?)",(*values.values(),scope))
            db.execute(f"UPDATE {table} SET state='ERROR' WHERE account_scope=?",("account-a",))
            assert db.execute(f"SELECT account_scope,state FROM {table} ORDER BY account_scope").fetchall()==[
                ("","VERIFIED"),("account-a","ERROR"),("account-b","VERIFIED")]
            assert db.execute(f"SELECT count(*) FROM {table} WHERE account_scope=? AND account_scope<>''",("",)).fetchone()==(0,)
    rounder=ROOT/"rounder/src/main/java/com/openlocal/office/core"
    client=(rounder/"FgTeraboxClient.java").read_text(encoding="utf-8")
    index=(rounder/"FgTeraboxSearchIndex.java").read_text(encoding="utf-8")
    provider=(rounder/"FgTeraboxDocumentsProvider.java").read_text(encoding="utf-8")
    assert 'fixedScope.equals(snapshot.get("preview_scope"))' in client
    assert 'FgTeraboxClient.forScope(context,scope)' in index
    assert 'DELETE FROM objects WHERE scope<>?' not in index
    assert 'return FgTeraboxClient.forScope(context,pinned)' in provider
    print("PASS: account-scoped ledger migration and isolation SQL fixtures")

def owner_taxonomy_regression():
    import hashlib
    root=ROOT/"fusion/src/main/assets/nexus_reference_knowledge/owner_taxonomy_20261128"
    manifest=json.loads((root/"manifest.json").read_text(encoding="utf-8"))
    ids=set()
    series=set()
    relations=[]
    for name,expected in manifest["files"].items():
        raw=(root/name).read_bytes()
        assert hashlib.sha256(raw).hexdigest()==expected["sha256"], name
        doc=json.loads(raw)
        rows=doc if isinstance(doc,list) else [x for v in doc.values() for x in v]
        assert len(rows)==expected["entries"],name
        for row in rows:
            if "series_code" in row:
                key=row["series_code"]
                assert key not in series
                series.add(key)
            else:
                key=row.get("tag_id",row.get("id"))
                assert key and key not in ids
                ids.add(key)
                for field in ("parent_action","parent_tag","parent_outfit","parent_weapon",
                              "related_species","primary_eye_state","primary_mouth_state"):
                    value=row.get(field)
                    relations.extend(value if isinstance(value,list) else [value] if value else [])
    assert len(ids)==1278 and len(series)==1621
    assert set(relations)<=ids
    assert not manifest["character_knowledge_complete"]
    print("PASS: exact owner taxonomy archive documents and relationship closure")

if __name__=="__main__":
    check()
