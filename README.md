# TwiCrates

TwiCrates is a security-hardened ExcellentCrates fork focused on first-class Java and Bedrock presentation. It keeps the proven reward, cost, limit and opening pipeline intact while adding resource-pack crate models, Geyser/Floodgate-aware block views and native Bedrock forms.

Original project by NightExpress. TwiCrates fork development by siberanka.

## Highlights

- Packet-based, per-player Java resource-pack models using `ItemDisplay`, modern `Item_Model`, legacy `Custom_Model_Data`, scale, offset and yaw controls.
- Optional external model selection from BetterModel, ModelEngine and MythicMobs IDs through commands and paginated editor menus, including BetterModel/ModelEngine animation-state discovery and viewer-scoped BetterModel rendering.
- Per-crate Bedrock/Geyser blocks. A Bedrock player can see a chest, barrel or another safe vanilla block where a Java player sees the resource-pack model.
- Placement-specific cardinal facing. The Java model and directional Bedrock block use the same stored direction.
- Native Bedrock crate overview, paginated reward browser, reward details and cost-selection forms through Geyser/Floodgate Cumulus.
- Resource-pack status gating: models can be hidden until a Java client reports that the pack loaded successfully.
- Automatic Bedrock re-synchronization after join, teleport, respawn, world/chunk movement and periodic server block refreshes.
- Existing crate rewards, costs, limits, cooldowns, openings, keys, holograms and particles remain on the ExcellentCrates pipeline.
- Crate particle effect centers can be raised or lowered per crate from YAML, command or editor dialog without changing reward logic.
- Backward-compatible Java packages/API and command aliases. `plugin.yml` also provides the `ExcellentCrates` capability for integrations.

## Safe display design

TwiCrates converts a block linked by `/twicrate set` into a real server-side `BARRIER`. This guarantees an invisible, authoritative interaction/collision anchor even if a packet backend is absent or temporarily unavailable. Java players receive the configured lightweight model; Bedrock players receive their configured client-side vanilla block at the same coordinate. Unlinking or deleting the crate removes only the barrier owned by that linked position.

Any safe, non-container solid block can be used as the temporary link target; linking deliberately replaces it with the managed barrier anchor.

This design avoids item drops, model-entity pickup, piston desynchronization, ghost blocks and chunk-save duplication. Linked blocks are protected against breaking, explosions, piston movement and entity block conversion. Inventory-holder blocks are rejected by `/twicrate set` so linking a crate cannot trap or expose container contents.

## Requirements

- Java 21 or newer
- Spigot/Paper 1.21.8 or newer (compiled against 1.21.10 API)
- NightCore `2.16.1-fork`
- Optional: Geyser-Spigot and/or Floodgate for Bedrock detection and native forms
- Optional: PacketEvents (preferred) or ProtocolLib for packet-based Java models and holograms
- Optional: BetterModel, ModelEngine or MythicMobs for provider-backed model ID selection
- Optional: CraftEngine for custom item rewards and crate base items through NightCore's item bridge

TwiCrates currently follows the upstream plugin's non-Folia scheduler model.

## Quick setup

1. Install TwiCrates and its matching NightCore build.
2. Configure the crate's `Block.Display` section in `plugins/TwiCrates/crates/<crate>.yml`.
3. Place a barrier or another non-container solid block.
4. Look directly at the block and run `/twicrate set vote`.
5. Reload TwiCrates after manual YAML changes.

The placement faces toward the player who runs the command. Fine-tune model orientation with `Yaw_Offset`. TwiCrates writes detailed English comments into every saved crate YAML.

Packet rendering is enabled by default in `config.yml`:

```yaml
Crate:
  Packet-Based_Mode: true
```

When enabled, Java crate models and holograms use the detected packet backend; PacketEvents is preferred when both integrations are installed. The physical linked location remains a real `BARRIER`, while Bedrock receives its configured client-side block. BetterModel viewer trackers operate independently of PacketEvents/ProtocolLib. Item-model displays safely fall back to managed Bukkit `ItemDisplay` entities if neither packet backend is available or a backend fails at runtime. Setting it to `false` uses Bukkit entities for item-model crate displays, while holograms keep their existing packet implementation.

## Crate display example

```yaml
Block:
  Effect:
    Enabled: true
    Model: HELIX
    # Added on top of the Java display base height and idle model Y_Offset.
    # Use this to move the particle animation center up or down around the visible model.
    Y_Offset: 0.0
  Display:
    Enabled: true
    Default_Facing: SOUTH
    # Managed by TwiCrates. Each entry is x,y,z,world,DIRECTION.
    Facings: []
    Java:
      Enabled: true
      Models:
        Idle:
          Provider: item_model
          Model_Id:
          State:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_idle'
          Custom_Model_Data: 10001
          Y_Offset: 0.0
        Opening:
          Enabled: true
          Provider: item_model
          Model_Id:
          State:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_opening'
          Custom_Model_Data: 10002
          Y_Offset: 0.0
        Closing:
          Enabled: true
          Provider: item_model
          Model_Id:
          State:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_closing'
          Custom_Model_Data: 10003
          Y_Offset: 0.0
      Scale: 1.0
      Y_Offset: 0.5
      Yaw_Offset: 0.0
      Require_Accepted_Resource_Pack: false
    Bedrock:
      Enabled: true
      Blocks:
        Idle: CHEST
        Opening: TRIAL_SPAWNER
        Closing: VAULT
      Forms:
        Enabled: true

Animation:
  # Earliest reward delivery time. Longer opening providers are not cut short.
  Reward_Delivery_Delay_Ticks: 60
  Closing_Model_Duration_Ticks: 20
```

Opening and closing models are per-player in packet mode: the opener sees the active phase while other players keep the idle model. Each Java phase has its own `Y_Offset`, added on top of the shared `Block.Display.Java.Y_Offset`, so idle/opening/closing animations can be nudged up or down independently. If the opening model/block is disabled or empty, idle remains visible. If closing is absent, the display returns directly to idle after reward delivery. The Bukkit fallback applies the safest aggregate phase globally because server entities cannot carry different item metadata per viewer.

Crate particle effects are emitted from the Java display base height plus the idle model `Y_Offset`, then adjusted by `Block.Effect.Y_Offset`. This lets the effect orbit the visible crate model even when the model itself is moved. `Block.Effect.Y_Offset` is clamped to `-16.0..16.0`.

For Java model phases, `Provider: item_model` keeps the resource-pack `ItemDisplay` path. `Provider: bettermodel`, `modelengine` or `mythicmobs` stores the selected provider model id in `Model_Id`. BetterModel and ModelEngine models can additionally set `State` to one of the animations exposed by that concrete model (for example `idle`, `open` or `close`); an empty value uses the provider default. BetterModel displays use viewer-scoped `DummyTracker` instances, so opening/closing states remain private to the opener and no server entity is created. If a provider is absent, reloading or cannot expose/create the id safely, TwiCrates closes stale handles and keeps the item-model fallback instead of crashing.

The crate editor exposes **Java & Bedrock Display**, **External Model Browser** and **CraftEngine Base Item** actions. The external model browser is paginated and cycles idle/opening/closing plus item_model/BetterModel/ModelEngine/MythicMobs providers. Selecting a BetterModel or ModelEngine model opens a second paginated state browser populated from that model's live API; `default` clears the explicit state. Reward item content has a **CraftEngine Items** browser for adding CraftEngine custom items directly. The existing **Opening Animation** dialog also edits reward-delivery and closing-state durations. All labels and Bedrock form text use the normal TwiCrates language-entry system.

Useful Bedrock block examples include `CHEST`, `BARREL`, `ENDER_CHEST`, `TRIAL_SPAWNER` and a full value such as `minecraft:chest[type=single,waterlogged=false,facing=north]`. Invalid, air or non-block values safely fall back to `CHEST`.

## Commands and permissions

- `/twicrate model <crate> <idle|opening|closing> <item_model|bettermodel|modelengine|mythicmobs> <id> [state]` - sets the Java display model source; model IDs and BetterModel/ModelEngine states are context-aware tab completions. Use `default` or omit the state to clear an explicit state.
  Selecting a model through this command or the model browser automatically enables the crate display, Java display and selected phase.
- `/twicrate effectoffset <crate> <y_offset>` - sets the crate particle effect center offset, clamped to `-16.0..16.0`.
- `/twicrate craftengine base <crate> <item-id>` - sets the crate base item from a CraftEngine custom item.
- `/twicrate craftengine reward <crate> <reward-id> <item-id> [amount]` - adds a CraftEngine custom item to an item reward.

- `/twicrate set <crate>` — links the targeted non-container block and records its facing.
- `/twicrate reload` — reloads the plugin and recreates displays.
- All original ExcellentCrates aliases remain available, including `/crates` and `/excellentcrates`.

The new placement/model permissions are `twicrates.command.set`, `twicrates.command.model`, `twicrates.command.effectoffset` and `twicrates.command.craftengine`. Existing ExcellentCrates permissions remain unchanged for compatibility.

## Bedrock behavior

When a local Geyser or Floodgate API is available, Bedrock players receive:

- a configured vanilla block instead of the Java item model;
- the same north/east/south/west orientation as the Java model;
- native forms for crate details, paginated rewards, reward descriptions and cost selection;
- core restriction, permission, cooldown, inventory-space, affordability and opening-availability checks immediately before an opening;
- the normal Geyser-translated opening animation, hologram and particle pipeline after the form selection.

If the platform API is unavailable or forms are disabled for a crate, TwiCrates falls back to the upstream inventory interaction. No reward is issued from a form callback; forms only select an action, then the existing authoritative opening pipeline performs every validation and reward operation.

## Reliability and security controls

- All Bukkit world, entity, inventory and opening actions run on the server thread.
- Form callbacks revalidate player state, crate ownership and source location.
- Reward delay is enforced at the single authoritative opening completion point; costs are not consumed again and rewards are not duplicated by display transitions.
- Display phase state is scoped by player and physical crate location, bounded by active openings, and cleared on quit, chunk unload, reload and shutdown.
- A per-player action debounce prevents repeated Bedrock form responses from starting duplicate openings.
- Display scale and offsets are bounded; form text, reward pages and block updates are capped.
- Packet mode creates no server-side Java model entities. Per-player viewer sets are distance/chunk bounded and cleared on quit, chunk unload, reload and shutdown.
- The Bukkit fallback uses non-persistent, invulnerable, gravity-free displays tracked by UUID and removed on chunk unload/plugin shutdown.
- Crate particle effects are emitted from the Java model's idle display height and use force-enabled player particle packets with a safe NightCore fallback, so packet-based crate blocks do not suppress the surrounding effect animation.
- Online players are resynchronized after startup/reload and again after join, teleport, respawn, world change and chunk movement; a bounded periodic reconciliation repairs missed client packets.
- Per-player virtual linked-block views are bounded, reconciled by distance/chunk and restored to the real server block on crate removal, reload and shutdown.
- Player/resource-pack/form tracking is cleared on quit and shutdown.
- Invalid model materials, item-model keys and Bedrock block data fail closed to safe defaults.
- No shell, expression-language, reflection-selected command or user-controlled class loading is exposed by the new features.
- TwiCrates does not add Log4j and does not log raw form payloads.

These controls reduce the attack surface for memory leaks, dupes, command injection, crashes and lag. Production operators should still test their exact Geyser version, Bedrock resource pack, custom model geometry and opening providers on a staging server before rollout.

## Build

```powershell
$env:JAVA_HOME = 'F:\vds\Java\jdk-25.0.2+10'
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
mvn clean package
```

The plugin jar is produced under `target/TwiCrates-<version>.jar`.

## License

TwiCrates remains distributed under the repository's GPL-3.0 license. See `LICENSE`.

---

# TwiCrates Türkçe

TwiCrates, Java ve Bedrock tarafında modern kasa gösterimine odaklanan, güvenlik açısından güçlendirilmiş bir ExcellentCrates forkudur. Kanıtlanmış ödül, ücret, limit ve açılış sistemi korunur; buna ek olarak resource pack kasa modelleri, Geyser/Floodgate uyumlu blok görünümleri ve native Bedrock formları eklenir.

Orijinal proje NightExpress tarafından geliştirilmiştir. TwiCrates fork geliştirmesi siberanka tarafından yapılmaktadır.

## Öne çıkanlar

- `ItemDisplay`, modern `Item_Model`, eski `Custom_Model_Data`, ölçek, offset ve yaw kontrolleriyle oyuncu bazlı paket tabanlı Java resource pack modelleri.
- Komutlar ve sayfalı editör menüleri üzerinden BetterModel, ModelEngine ve MythicMobs model ID seçimi. BetterModel/ModelEngine animasyon state keşfi ve oyuncuya özel BetterModel render desteği dahildir.
- Kasa başına Bedrock/Geyser bloğu. Java oyuncusu resource pack modelini görürken Bedrock oyuncusu aynı konumda sandık, varil veya güvenli başka bir vanilla blok görebilir.
- Yerleşim bazlı yön desteği. Java modeli ve yönlü Bedrock bloğu aynı kayıtlı yönü kullanır.
- Geyser/Floodgate Cumulus ile native Bedrock kasa özeti, sayfalı ödül tarayıcı, ödül detayları ve ücret seçme formları.
- Resource pack durumu kontrolü: Java client resource pack’i başarıyla yüklediğini bildirene kadar modeller gizlenebilir.
- Join, teleport, respawn, dünya/chunk hareketleri ve periyodik server blok yenilemeleri sonrasında otomatik Bedrock yeniden senkronizasyonu.
- Mevcut kasa ödülleri, ücretleri, limitleri, cooldownları, açılışları, anahtarları, hologramları ve partikülleri ExcellentCrates sistemi üzerinde kalır.
- Kasa partikül efekt merkezleri YAML, komut veya editör dialogu üzerinden kasa başına yukarı/aşağı alınabilir; ödül mantığına dokunulmaz.
- Java package/API ve komut aliasları geriye dönük uyumludur. `plugin.yml` entegrasyonlar için `ExcellentCrates` capability bilgisini de sağlar.

## Güvenli gösterim tasarımı

TwiCrates, `/twicrate set` ile bağlanan bloğu gerçek server-side `BARRIER` bloğuna çevirir. Bu, paket backend’i yoksa veya geçici olarak kullanılamıyorsa bile görünmez ve otoriter bir etkileşim/çarpışma noktası sağlar. Java oyuncuları ayarlanan hafif modeli, Bedrock oyuncuları ise aynı koordinatta kendileri için ayarlanan client-side vanilla bloğu görür. Kasayı unlink etmek veya silmek yalnızca o bağlı konuma ait barrier bloğunu kaldırır.

Geçici link hedefi olarak güvenli, container olmayan herhangi bir solid blok kullanılabilir; linkleme işlemi bu bloğu bilinçli olarak yönetilen barrier anchor ile değiştirir.

Bu tasarım item düşmesi, model entity pickup, piston desync, ghost block ve chunk-save duplication risklerini azaltır. Bağlı bloklar kırılmaya, patlamalara, piston hareketine ve entity block dönüşümüne karşı korunur. Inventory-holder bloklar `/twicrate set` tarafından reddedilir; böylece kasa linkleme container içeriğini kilitleyemez veya açığa çıkaramaz.

## Gereksinimler

- Java 21 veya daha yeni
- Spigot/Paper 1.21.8 veya daha yeni (1.21.10 API’ye karşı derlenmiştir)
- NightCore `2.16.1-fork`
- Opsiyonel: Bedrock tespiti ve native formlar için Geyser-Spigot ve/veya Floodgate
- Opsiyonel: Paket tabanlı Java modelleri ve hologramlar için PacketEvents (önerilir) veya ProtocolLib
- Opsiyonel: Provider destekli model ID seçimi için BetterModel, ModelEngine veya MythicMobs
- Opsiyonel: NightCore item bridge üzerinden custom item ödülleri ve kasa base itemleri için CraftEngine

TwiCrates şu anda upstream plugin’in Folia dışı scheduler modelini takip eder.

## Hızlı kurulum

1. TwiCrates’i ve uyumlu NightCore buildini kur.
2. Kasanın `plugins/TwiCrates/crates/<crate>.yml` dosyasındaki `Block.Display` bölümünü ayarla.
3. Bir barrier veya container olmayan başka bir solid blok koy.
4. Bloğa bakarak `/twicrate set vote` komutunu çalıştır.
5. YAML değişikliklerinden sonra TwiCrates’i reload et.

Yerleşim, komutu çalıştıran oyuncunun baktığı yöne göre kaydedilir. Model yönünü ince ayarlamak için `Yaw_Offset` kullan. TwiCrates kaydedilen her kasa YAML dosyasına ayrıntılı İngilizce yorum satırları yazar.

Paket render varsayılan olarak `config.yml` içinde açıktır:

```yaml
Crate:
  Packet-Based_Mode: true
```

Bu açıkken Java kasa modelleri ve hologramlar algılanan paket backend’ini kullanır; iki entegrasyon da varsa PacketEvents tercih edilir. Fiziksel bağlı konum gerçek `BARRIER` olarak kalır, Bedrock ise kendi ayarlı client-side bloğunu görür. BetterModel viewer tracker’ları PacketEvents/ProtocolLib’den bağımsız çalışır. Item-model display’ler, paket backend’i yoksa veya runtime’da hata verirse güvenli şekilde yönetilen Bukkit `ItemDisplay` entity’lerine düşer. Ayarı `false` yapmak item-model kasa gösterimleri için Bukkit entity kullanır; hologramlar mevcut paket sistemini korur.

## Kasa gösterim örneği

```yaml
Block:
  Effect:
    Enabled: true
    Model: HELIX
    # Java display base height ve idle model Y_Offset üzerine eklenir.
    # Partikül animasyon merkezini görünen modelin çevresinde yukarı/aşağı almak için kullanılır.
    Y_Offset: 0.0
  Display:
    Enabled: true
    Default_Facing: SOUTH
    # TwiCrates tarafından yönetilir. Her kayıt x,y,z,world,DIRECTION formatındadır.
    Facings: []
    Java:
      Enabled: true
      Models:
        Idle:
          Provider: item_model
          Model_Id:
          State:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_idle'
          Custom_Model_Data: 10001
          Y_Offset: 0.0
        Opening:
          Enabled: true
          Provider: item_model
          Model_Id:
          State:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_opening'
          Custom_Model_Data: 10002
          Y_Offset: 0.0
        Closing:
          Enabled: true
          Provider: item_model
          Model_Id:
          State:
          Material: PAPER
          Item_Model: 'twicrates:vote_crate_closing'
          Custom_Model_Data: 10003
          Y_Offset: 0.0
      Scale: 1.0
      Y_Offset: 0.5
      Yaw_Offset: 0.0
      Require_Accepted_Resource_Pack: false
    Bedrock:
      Enabled: true
      Blocks:
        Idle: CHEST
        Opening: TRIAL_SPAWNER
        Closing: VAULT
      Forms:
        Enabled: true

Animation:
  # En erken ödül verme süresi. Daha uzun opening provider animasyonları kesilmez.
  Reward_Delivery_Delay_Ticks: 60
  Closing_Model_Duration_Ticks: 20
```

Opening ve closing modelleri paket modunda oyuncu bazlıdır: kasayı açan oyuncu aktif fazı görürken diğer oyuncular idle modeli görmeye devam eder. Her Java fazının kendi `Y_Offset` değeri vardır; bu değer ortak `Block.Display.Java.Y_Offset` üzerine eklenir. Böylece idle/opening/closing animasyonları ayrı ayrı yukarı veya aşağı alınabilir. Opening model/blok kapalıysa veya boşsa idle görünür kalır. Closing yoksa ödül verildikten sonra display doğrudan idle’a döner. Bukkit fallback, server entity’leri oyuncu başına farklı item metadata taşıyamadığı için en güvenli global fazı uygular.

Kasa partikül efektleri Java display base height ve idle model `Y_Offset` yüksekliğinden çıkar, ardından `Block.Effect.Y_Offset` ile ayarlanır. Böylece modelin kendisi taşınmış olsa bile efekt görünen kasa modelinin etrafında dönebilir. `Block.Effect.Y_Offset` değeri `-16.0..16.0` aralığına clamp edilir.

Java model fazlarında `Provider: item_model`, resource pack `ItemDisplay` yolunu kullanır. `Provider: bettermodel`, `modelengine` veya `mythicmobs` seçildiğinde provider model ID’si `Model_Id` altında saklanır. BetterModel ve ModelEngine modelleri ayrıca ilgili model API’sinden gelen animasyon state’lerinden birini `State` olarak kullanabilir; örneğin `idle`, `open` veya `close`. Boş değer provider varsayılanını kullanır. BetterModel display’leri oyuncuya özel `DummyTracker` instance’ları kullanır; bu nedenle opening/closing state’leri sadece kasayı açan oyuncuya özel kalır ve server entity oluşturulmaz. Provider yoksa, reload oluyorsa veya ID güvenli şekilde oluşturulamıyorsa TwiCrates stale handle’ları kapatır ve crash atmak yerine item-model fallback’i korur.

Kasa editörü **Java & Bedrock Display**, **External Model Browser** ve **CraftEngine Base Item** aksiyonlarını sunar. External model browser sayfalıdır ve idle/opening/closing ile item_model/BetterModel/ModelEngine/MythicMobs provider’ları arasında geçiş yapar. BetterModel veya ModelEngine modeli seçildiğinde, o modelin live API’sinden gelen state’lerle ikinci bir sayfalı state browser açılır; `default` açık state seçimini temizler. Ödül item içeriğinde CraftEngine custom itemlerini doğrudan eklemek için **CraftEngine Items** browser bulunur. Mevcut **Opening Animation** dialogu ayrıca ödül verme gecikmesini ve closing-state süresini düzenler. Tüm etiketler ve Bedrock form metinleri normal TwiCrates language-entry sistemini kullanır.

Kullanışlı Bedrock blok örnekleri: `CHEST`, `BARREL`, `ENDER_CHEST`, `TRIAL_SPAWNER` veya tam blok datası olarak `minecraft:chest[type=single,waterlogged=false,facing=north]`. Geçersiz, air veya blok olmayan değerler güvenli şekilde `CHEST` fallback’ine düşer.

## Komutlar ve izinler

- `/twicrate model <crate> <idle|opening|closing> <item_model|bettermodel|modelengine|mythicmobs> <id> [state]` - Java display model kaynağını ayarlar. Model ID’leri ve BetterModel/ModelEngine state’leri context-aware tab completion ile gelir. Açık state’i temizlemek için `default` kullan veya state parametresini boş bırak.
  Bu komutla veya model browser üzerinden model seçmek kasa display’ini, Java display’i ve seçilen fazı otomatik etkinleştirir.
- `/twicrate effectoffset <crate> <y_offset>` - kasa partikül efekt merkez offsetini ayarlar; değer `-16.0..16.0` aralığına clamp edilir.
- `/twicrate craftengine base <crate> <item-id>` - kasa base itemini CraftEngine custom iteminden ayarlar.
- `/twicrate craftengine reward <crate> <reward-id> <item-id> [amount]` - item reward içine CraftEngine custom item ekler.

- `/twicrate set <crate>` - bakılan container olmayan bloğu kasaya bağlar ve yönünü kaydeder.
- `/twicrate reload` - plugini reload eder ve display’leri yeniden oluşturur.
- `/crates` ve `/excellentcrates` dahil tüm orijinal ExcellentCrates aliasları kullanılabilir kalır.

Yeni placement/model izinleri: `twicrates.command.set`, `twicrates.command.model`, `twicrates.command.effectoffset` ve `twicrates.command.craftengine`. Mevcut ExcellentCrates izinleri uyumluluk için korunur.

## Bedrock davranışı

Yerel Geyser veya Floodgate API kullanılabiliyorsa Bedrock oyuncuları şunları alır:

- Java item modeli yerine ayarlanmış vanilla blok;
- Java modeliyle aynı north/east/south/west yönü;
- kasa detayları, sayfalı ödüller, ödül açıklamaları ve ücret seçimi için native formlar;
- açılıştan hemen önce temel restriction, permission, cooldown, inventory-space, affordability ve opening-availability kontrolleri;
- form seçiminden sonra normal Geyser tarafından çevrilen opening animasyonu, hologram ve partikül pipeline’ı.

Platform API yoksa veya formlar kasa için kapalıysa TwiCrates upstream inventory interaction sistemine düşer. Form callback’inden ödül verilmez; formlar yalnızca aksiyon seçer, ardından mevcut otoriter opening pipeline tüm validasyonları ve ödül işlemlerini yapar.

## Güvenilirlik ve güvenlik kontrolleri

- Tüm Bukkit world, entity, inventory ve opening işlemleri server thread üzerinde çalışır.
- Form callback’leri oyuncu durumunu, crate ownership’i ve source location’ı yeniden doğrular.
- Reward delay tek otoriter opening completion noktasında uygulanır; display geçişleri cost’u tekrar tüketmez ve ödülü çoğaltmaz.
- Display faz durumu oyuncu ve fiziksel kasa konumu bazlıdır, aktif opening’lerle sınırlandırılır ve quit, chunk unload, reload ve shutdown sırasında temizlenir.
- Oyuncu başına action debounce, tekrarlanan Bedrock form cevaplarının duplicate opening başlatmasını engeller.
- Display scale ve offsetler sınırlandırılır; form metni, reward page ve block update değerleri cap’lenir.
- Packet mode Java model entity’si oluşturmaz. Oyuncu başına viewer setleri distance/chunk bazlı sınırlandırılır ve quit, chunk unload, reload ve shutdown sırasında temizlenir.
- Bukkit fallback persistent olmayan, invulnerable, gravity-free display entity’leri kullanır; UUID ile takip edilir ve chunk unload/plugin shutdown sırasında kaldırılır.
- Kasa partikül efektleri Java modelin idle display yüksekliğinden çıkar ve güvenli NightCore fallback’iyle force-enabled oyuncu particle packet’leri kullanır; bu yüzden packet-based kasa blokları çevredeki efekt animasyonunu bastırmaz.
- Online oyuncular startup/reload sonrasında, ayrıca join, teleport, respawn, world change ve chunk movement sonrasında yeniden senkronize edilir; bounded periodic reconciliation kaçan client packet’lerini onarır.
- Oyuncu başına virtual linked-block görünümleri bounded tutulur, distance/chunk bazlı reconcile edilir ve crate removal, reload ve shutdown sırasında gerçek server bloğuna döndürülür.
- Player/resource-pack/form takipleri quit ve shutdown sırasında temizlenir.
- Geçersiz model materialları, item-model keyleri ve Bedrock block data değerleri güvenli varsayılanlara düşer.
- Yeni özellikler shell, expression-language, reflection ile seçilen command veya user-controlled class loading açmaz.
- TwiCrates Log4j eklemez ve raw form payload loglamaz.

Bu kontroller memory leak, dupe, command injection, crash ve lag saldırı yüzeyini azaltır. Production’a almadan önce yine de kendi Geyser sürümünü, Bedrock resource pack’ini, custom model geometry’lerini ve opening provider’larını staging sunucuda test etmen önerilir.

## Build

```powershell
$env:JAVA_HOME = 'F:\vds\Java\jdk-25.0.2+10'
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT'
mvn clean package
```

Plugin jar dosyası `target/TwiCrates-<version>.jar` altında oluşturulur.

## Lisans

TwiCrates, repository’nin GPL-3.0 lisansı altında dağıtılmaya devam eder. Detaylar için `LICENSE` dosyasına bak.
