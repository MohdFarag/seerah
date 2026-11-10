# دراسة: الـ tech stack المناسب لتطبيق «السيرة» على Android

- **تاريخ البحث:** 2026-11-10
- **الحالة:** دراسة وترشيح من المساعد للمناقشة. ليست قرارًا معتمدًا ولا نتيجة تجربة على جهاز. القرار يبقى في [ADR 0002](../adr/0002-proposed-stack.md) بعد الـ spike.
- **النطاق:** Android (هاتف وتابلت)، عربي RTL، خريطة 2D، يعمل دون إنترنت، مجاني. هدف الدراسة: اختيار ما يجعل تنفيذ تصميمات Penpot وإنتاج الخرائط أسرع وأدق.

## الخلاصة

الترشيح: **Kotlin + Jetpack Compose + MapLibre Native Android + PMTiles/GeoJSON + Room**. هذا يؤكد ADR 0002 ولا يغيّره، مع خمس إضافات:

1. قرار مستقل لطريقة رسم الأسماء العربية على الخريطة (القسم 4)، لأنه أكبر خطر تقني.
2. خط إنتاج للـ design tokens من Penpot إلى Compose بدل نقل الألوان والمقاسات يدويًا.
3. اختبارات لقطات شاشة (screenshot tests) تقارن الشاشات بتصدير Penpot.
4. حسم نسخة العرض: Vulkan أم OpenGL ES، حسب أجهزة الاختبار.
5. تقييم `maplibre-compose` داخل الـ spike، مع إبقاء الـ SDK المباشر خيارًا آمنًا.

## 1. ما الذي يحسم الاختيار

| المعيار | لماذا يهم هنا |
| --- | --- |
| رسم العربية داخل الخريطة | أسماء متصلة، وبعضها مشكول أو مخطوط (Amiri)، وهو مسار منفصل عن نص الواجهة |
| العمل دون إنترنت | حزمة نوزعها نحن، تحديث منفصل عن إصدار التطبيق، تنزيل قابل للاستكمال |
| مطابقة Penpot | 12 نمط خط، ثلاث مجموعات tokens (أساس، نهاري، ليلي)، نحو 110 مكوّنات، تخطيطات flex |
| سلاسة الخريطة على أجهزة ضعيفة | الخريطة هي الشاشة الرئيسية |
| حجم الفريق | صيانة كود واحد أهم من مزايا نظرية |

## 2. إطار الواجهة

| المحور | Kotlin + Compose | Flutter | React Native | Compose Multiplatform | Capacitor (ويب داخل WebView) |
| --- | --- | --- | --- | --- | --- |
| الوصول لمحرك الخريطة | مباشر، كل الـ API | عبر إضافة، تتأخر عن المحرك | عبر إضافة، v11 يتطلب المعمارية الجديدة | عبر `maplibre-compose` | محرك الويب GL JS لا Native |
| نص الواجهة العربي | محرك النظام، ممتاز | محرك خاص بـ Flutter، جيد | محرك النظام | مثل Compose على Android | محرك المتصفح، ممتاز |
| الملفات والتنزيل في الخلفية | مباشر (WorkManager) | إضافات | إضافات | مباشر على Android | تخزين WebView قد يُسترجع من النظام؛ يلزم جسر native |
| مطابقة Penpot | عالية: flex يقابل Row/Column مباشرة | عالية | متوسطة | عالية | الأعلى (CSS)، لكن خارج المسار المختار |
| iOS لاحقًا | إعادة كتابة الواجهة | نفس الكود | نفس الكود | مشاركة أغلب الكود | نفس الكود |
| مخاطر | الأقل على Android | استقرار الإضافة | طبقتان فوق المحرك | مكتبة قبل 1.0 | أداء WebGL داخل WebView غير مثبت |

**الحكم:** Compose هو الأنسب ما دام Android وحده في النطاق. Flutter يصبح الأرجح فقط إذا تأكد أن iOS قريب. تقييم الأداء والمطابقة في هذا الجدول تقدير هندسي، لا قياس.

ملاحظات موثقة:

- إضافة Flutter الرسمية `maplibre_gl` تدعم PMTiles على Android وiOS والويب.
- صاحب الحزمة البديلة `maplibre` ينقل استبيانًا فيه أن 44% من مستخدمي `maplibre_gl` واجهوا أعطالًا. المصدر منحاز، فيؤخذ كإشارة لا كحقيقة.
- MapLibre React Native v11 يدعم المعمارية الجديدة فقط (React Native 0.80 فأعلى).
- وثائق Capacitor نفسها تقول إن النظام قد يسترجع تخزين WebView عند امتلاء الجهاز.

## 3. محرك الخريطة

| المحور | MapLibre Native | Mapbox Maps SDK | Google Maps SDK | osmdroid / Mapsforge |
| --- | --- | --- | --- | --- |
| التكلفة | مجاني، BSD-2-Clause | حسب المستخدمين النشطين شهريًا | حسب الاستخدام | مجاني |
| حزمة دون إنترنت نملكها | نعم: ملف PMTiles محلي | Tile Packs الموثقة للبلاطات المستضافة عند Mapbox فقط | قيود على تخزين البلاطات | نعم بصيغ خاصة |
| تصميم مخصص بالكامل | نعم (style JSON) | نعم، مع Studio | محدود | محدود |
| بيانات تاريخية متغيرة زمنيًا | GeoJSON + فلاتر | مثلها | ضعيف | يدوي |
| أدوات تصميم | Maputnik (مفتوح) | Studio (أفضل) | — | — |

**الحكم:** MapLibre Native. أرقام تسعير Mapbox المتداولة (25 ألف مستخدم مجانًا ثم نحو 4 دولارات لكل ألف) من مصادر ثانوية ولم أتحقق منها من صفحة Mapbox.

حقائق موثقة عن MapLibre Native Android:

- PMTiles مدعوم منذ 11.7.0، من تخزين الجهاز بصيغة `pmtiles://file://`. **لا يُقرأ من assets** لأنها لا تدعم القراءة الجزئية.
- مصادر PMTiles لا تستخدم Offline Packs ولا التخزين المؤقت؛ التطبيق ينزّل الملف ويديره بنفسه.
- منذ 13.0.0 صار Vulkan هو الافتراضي في `android-sdk`، وOpenGL ES في حزمة `android-sdk-opengl`.
- أحدث إصدار ظهر في البحث هو 13.4.0، ولم أتأكد أنه الأحدث فعلًا.

### `maplibre-compose` أم الـ SDK المباشر

| | الـ SDK المباشر داخل `AndroidView` | `maplibre-compose` |
| --- | --- | --- |
| النضج | مستقر وموثق | 0.20.0؛ الـ API ما زال يتغير وقد تكسر الإصدارات الصغرى التوافق |
| أسلوب الكتابة | أمري، يحتاج ربطًا يدويًا بحالة Compose | تصريحي، طبقات ومصادر كـ composables |
| تغطية خصائص المحرك | كاملة | بند «استكمال الخصائص الناقصة» معلّق في خارطة الطريق |
| iOS لاحقًا | لا | نعم |

**الحكم:** نبدأ الـ spike بالـ SDK المباشر لأنه يعزل مخاطر المحرك عن مخاطر المكتبة، ونجرب `maplibre-compose` على نفس العينة. نعتمده فقط إذا غطى PMTiles المحلي ومسار الخطوط المختار.

## 4. الأسماء العربية على الخريطة (أكبر خطر)

في MapLibre Native مساران للنص:

- **المسار التقليدي (glyph PBF):** يفترض حرفًا واحدًا لكل رمز، مع معالجة خاصة للعربية والعبرية. لم أجد مصدرًا يؤكد سلامة موضع التشكيل فيه.
- **المسار الجديد (`font-faces` مع ملفات TrueType وHarfBuzz):** أضيف في 2025 لدعم الكتابات المعقدة. يوجد بلاغ (iOS، الإصدار 6.28.0) عن ظهور العربية والكردية بترتيب معكوس، وإصلاحات له في مستودعات مرتبطة. **لم أجد تأكيدًا لحالة Android.**

لذلك نقارن ثلاث طرق في الـ spike بنفس الجمل (متصلة، مشكولة، مع أرقام، مع ﷺ):

| الطريقة | المزايا | العيوب |
| --- | --- | --- |
| أ. نص المحرك بالمسار التقليدي | تصادم الأسماء تلقائي، أبسط | خطر في التشكيل والخطوط المخطوطة |
| ب. نص المحرك عبر `font-faces` | تشكيل صحيح نظريًا، خطوطنا كما هي | مسار حديث وبه بلاغات RTL |
| ج. أسماء مرسومة مسبقًا كصور (رموز) للأسماء القليلة الخاصة | مطابقة تامة للتصميم (مثل «الحجاز» بخط Amiri) | لا تتغير مع حجم خط المستخدم، وتزيد حجم الحزمة |

**الترشيح المبدئي:** أسماء الأماكن على الخريطة بلا تشكيل بالطريقة أ أو ب حسب نتيجة الجهاز، والأسماء الزخرفية القليلة (المناطق والبحار) بالطريقة ج. النصوص المشكولة تبقى في واجهة Compose حيث التشكيل مضمون.

## 5. صيغ البيانات وخط إنتاج الخرائط

| الطبقة | الصيغة المرشحة | السبب |
| --- | --- | --- |
| الأساس (سواحل، تضاريس) | PMTiles | ملف واحد، قراءة جزئية، نملك توزيعه |
| التضاريس المظللة | بلاطات raster داخل PMTiles | مظهر الخريطة في التصميم مرسوم بتظليل، وهو أكبر عامل في الحجم |
| الطبقات التاريخية (أسماء، حدود، معالم، مسارات) | GeoJSON بخصائص بداية ونهاية | صغيرة، تتغير بالزمن، تُحدَّث مع المحتوى |
| MBTiles | بديل | مدعوم ومجرّب، لكنه قاعدة SQLite أثقل في التحديث الجزئي |

الأدوات: QGIS للتحرير والإسناد الجغرافي، GDAL للتحويل والتظليل، Tippecanoe للبلاطات المتجهة، Maputnik لتحرير الـ style، وأداة لتوليد الأيقونات (sprites). هذه السلسلة هي المعتادة ولا أرى بديلًا أفضل منها.

نقاط تحتاج قرارًا:

- **تبسيط الحدود:** Tippecanoe يبسّط الأشكال، وقد تنفصل حدود متجاورة بين حالتين زمنيتين. الحدود التاريخية تبقى GeoJSON غير مبسّطة.
- **مصدر الأساس:** بيانات حديثة (طرق، حدود دول) لا تصلح لخريطة تاريخية. نبدأ من سواحل وتضاريس فقط.
- **الترخيص والمصدر:** كل معلم له مصدر ودرجة ثقة في البيانات، ويرفض `tools/validate_content.py` ما لم يُراجع. هذا يطبق قاعدة «لا إحداثيات مخترعة».

## 6. من Penpot إلى Compose

| الخطوة | الخيارات | الترشيح |
| --- | --- | --- |
| الألوان والمقاسات | نقل يدوي؛ إضافة Penpot «Design Tokens Export & Deploy» (تذكر Compose ضمن مخرجاتها)؛ تصدير JSON ثم Style Dictionary بصيغة مخصصة | تصدير JSON ثم سكربت يولّد ملف Kotlin للثيم، يعمل في CI |
| الخطوط | ملفات داخل التطبيق | IBM Plex Sans Arabic وAmiri وAmiri Quran كموارد؛ يُراجع ترخيص كل خط |
| المكوّنات | كتابة يدوية؛ توليد بمساعدة الذكاء الاصطناعي من Penpot MCP | توليد أولي من MCP ثم مراجعة بشرية، مكوّن Compose لكل مكوّن Penpot |
| التحقق | مراجعة بالعين؛ screenshot tests | screenshot tests تقارن بتصدير Penpot |

تنبيهات:

- تصدير tokens من Penpot بصيغة Tokens Studio، لا DTCG 2025.10، بحسب بلاغ في منتدى Penpot. أدوات DTCG قد تُسقط قيمًا بصمت، فيلزم اختبار تصدير حقيقي.
- أداة `penpot-export` الرسمية تُخرج CSS وSCSS وJSON فقط.
- التصميم يستخدم `row-reverse` لمحاكاة RTL. في Compose نكتب `Row` عاديًا تحت اتجاه RTL ولا ننقل الانعكاس.
- مجموعة `athar/night` في Penpot مطابقة حاليًا للنهاري، فالوضع الليلي غير مصمم بعد.

## 7. التخزين والتحديث

| الحاجة | المرشح | البديل | ملاحظة |
| --- | --- | --- | --- |
| المحتوى (أحداث، مواقف، نصوص) | Room/SQLite | ملفات JSON تُقرأ مباشرة | SQLite يفتح باب البحث النصي لاحقًا |
| التقدم والإعدادات | DataStore | جدول منفصل في Room | منفصل عن ملفات المحتوى القابلة للاستبدال |
| تنزيل الحزمة | WorkManager مع استكمال جزئي | Play Asset Delivery | الأول يحدّث دون إصدار جديد؛ الثاني مربوط بإصدار التطبيق |
| سلامة التحديث | manifest وتجزئات، تفعيل بخطوة واحدة | — | مطلوب في PRD |

حدود Play Asset Delivery الموثقة: 512 MB لكل حزمة تُنزَّل لاحقًا، و1 GB لحزم التثبيت، و2 GB للإجمالي. لا يناسب شرط «تحديث محتوى دون إصدار جديد»، لكنه يصلح لتضمين حزمة أولية.

## 8. المخاطر المفتوحة

| الخطر | كيف نغلقه |
| --- | --- |
| العربية داخل المحرك على Android | اختبار الطرق الثلاث في القسم 4 على هاتف وتابلت |
| Vulkan على أجهزة قديمة | تجربة النسختين على أضعف جهاز مستهدف |
| حجم الحزمة بسبب التضاريس | قياس عينة قبل تحديد مستوى التكبير الأقصى |
| عدم نضج `maplibre-compose` | الـ SDK المباشر خيار احتياطي |
| صيغة tokens من Penpot | اختبار تصدير فعلي قبل بناء خط الإنتاج |

## 9. المقترح للـ spike

يضاف إلى بنود الـ spike في [خارطة الطريق](../roadmap.md):

1. نفس العينة بالـ SDK المباشر وبـ `maplibre-compose`.
2. الطرق الثلاث لرسم الأسماء، مع لقطات للمقارنة.
3. نسخة Vulkan ونسخة OpenGL ES على أضعف جهاز.
4. شاشة واحدة كاملة (الخريطة الرئيسية) مبنية من tokens مولّدة آليًا، مع screenshot test مقابل Penpot.

## ما لم أتحقق منه

- أحدث إصدار فعلي لـ MapLibre Native Android، والحد الأدنى لإصدار Android، ودعم صفحات 16 KB.
- حالة `font-faces` على Android تحديدًا.
- أسعار Mapbox من مصدرها الرسمي.
- تراخيص الخطوط الثلاثة.
- كل أحكام الأداء في الجداول تقدير هندسي، لا قياس.

## المصادر

- [MapLibre Native Android: PMTiles](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)
- [MapLibre Native android-v13.0.0](https://newreleases.io/project/github/maplibre/maplibre-native/release/android-v13.0.0) و[android-v13.4.0](https://newreleases.io/project/github/maplibre/maplibre-native/release/android-v13.4.0) و[android-v11.7.0](https://newreleases.io/project/github/maplibre/maplibre-native/release/android-v11.7.0)
- [MapLibre Compose](https://maplibre.org/maplibre-compose/) و[خارطة طريقه](https://maplibre.org/maplibre-compose/roadmap/)
- [بلاغ RTL مع font-faces في MapLibre Native (iOS)](https://github.com/maplibre/maplibre-native/issues/4513) و[إصلاح مرتبط](https://github.com/maplibre/maplibre-native-ffi/pull/1770)
- [MapLibre newsletter يوليو 2025: font-faces](https://maplibre.org/news/2025-08-04-maplibre-newsletter-july-2025/)
- [About text rendering in MapLibre](https://github.com/wipfli/about-text-rendering-in-maplibre)
- [Flutter MapLibre GL: مصفوفة الخصائص](https://maplibre.org/flutter-maplibre-gl/compare/feature-matrix/) و[PMTiles](https://maplibre.org/flutter-maplibre-gl/advanced/pmtiles/)
- [مقارنة حزمة maplibre لـ Flutter (مصدر منحاز)](https://flutter-maplibre.pages.dev/compare)
- [MapLibre React Native: البدء](https://maplibre.org/maplibre-react-native/docs/setup/getting-started) و[newsletter أبريل 2026](https://maplibre.org/news/2026-05-02-maplibre-newsletter-april-2026/)
- [Capacitor: Data storage](https://capacitorjs.com/docs/guides/storage)
- [Mapbox Android: التسعير](https://docs.mapbox.com/android/maps/guides/pricing/)
- [Penpot: Design Tokens](https://help.penpot.app/user-guide/design-tokens/) و[بلاغ صيغة التصدير](https://community.penpot.app/t/token-export-is-tokens-studio-format-not-dtcg-2025-10/10544)
- [Penpot plugin: Design Tokens Export & Deploy](https://penpot.app/penpothub/plugins/design-tokens-export-and-deploy) و[penpot-export](https://github.com/penpot/penpot-export)
- [Play Asset Delivery](https://developer.android.com/guide/playcore/asset-delivery)
