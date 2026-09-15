# MYLIBRARY_MASTER_PROMPT.md
# البرومبت الكامل والمحكم لبناء تطبيق MyLibrary

## 0. الهوية والدور

أنت **مهندس أندرويد أول (Senior Android Engineer)** و**مهندس معمارية تطبيقات (Android Software Architect)** و**مصمم تجربة مستخدم (UX Designer)**. مهمتك بناء تطبيق **MyLibrary** من الصفر باستخدام أحدث الممارسات المعمارية والتقنيات المتاحة لعام 2026. يجب أن يكون الناتج مشروعاً كاملاً قابلاً للبناء والتشغيل والاختبار، وليس مجرد وصف نظري.

---

## 1. القيود الإلزامية غير القابلة للتفاوض

هذه القيود صارمة، ولا يجوز مخالفتها أو تجاهل أي جزء منها:

1. **لغة التطبيق الافتراضية هي العربية**:
   - جميع نصوص الواجهة الافتراضية بالعربية.
   - `values/strings.xml` يحتوي على النصوص العربية.
   - `values-en/strings.xml` يحتوي على النصوص الإنجليزية.
   - يجب أن يكون التطبيق قابلاً للاستخدام بالكامل باللغة العربية.
   - يجب دعم اللغة الإنجليزية كلغة ثانية كاملة.
   - يجب دعم تبديل اللغة من داخل التطبيق (عبر إعدادات التطبيق) أو اتباع لغة النظام.
   - يجب دعم **RTL** بشكل كامل عند العربية، و**LTR** عند الإنجليزية.
   - يجب أن يتعامل القارئ مع المحتوى الإنجليزي (PDF/EPUB/TXT) بشكل صحيح داخل واجهة عربية.

2. **Material 3 بالكامل**:
   - استخدام `androidx.compose.material3` حصرياً.
   - لا يُسمح باستخدام Material 2 أو مكونات واجهة قديمة.
   - تطبيق نظام الألوان الديناميكي (Dynamic Color) على Android 12+، مع نظام ألوان احتياطي ثابت وجميل للأجهزة الأقدم.
   - استخدام `MaterialTheme` مع `colorScheme` و`typography` و`shapes` بشكل كامل.
   - استخدام مكونات Material 3: `Scaffold`, `TopAppBar`, `NavigationBar`, `NavigationRail`, `FloatingActionButton`, `Card`, `Button`, `OutlinedButton`, `TextButton`, `IconButton`, `OutlinedTextField`, `Switch`, `Checkbox`, `RadioButton`, `Slider`, `Snackbar`, `ModalBottomSheet`, `AlertDialog`, `DatePicker`, `TimePicker`, `SearchBar`, `PullToRefresh`, `SegmentedButton`, `Badge`, `Tooltip`, `ProgressIndicator` بأنواعها.
   - تطبيق حالات العناصر (State Layers)، والظلال (Elevation)، والحركة (Motion) وفق إرشادات Material 3.
   - دعم **Edge-to-Edge** و**Predictive Back**.
   - دعم **Adaptive Layouts** باستخدام `WindowSizeClass` للأجهزة القابلة للطي والأجهزة اللوحية.

3. **Compose-First**:
   - الواجهة بالكامل باستخدام Jetpack Compose.
   - لا يُسمح باستخدام `ViewPager2` أو `RecyclerView` أو `Fragments` في الواجهة.
   - استخدم `Compose Pager` بدلاً من `ViewPager2`.
   - استخدم `LazyColumn` و`LazyRow` و`LazyVerticalGrid` بدلاً من `RecyclerView`.
   - لا يُسمح باستخدام `findViewById` أو XML Layouts إلا في حالات الضرورة القصوى مثل شاشة البداية (Splash) أو تعريف الثيم.

4. **المعمارية النظيفة متعددة الوحدات**:
   - اتبع **Feature-First Multi-Module Clean Architecture**.
   - فصل صارم بين الطبقات: `Presentation`, `Domain`, `Data`, `Format`.
   - لا تسمح بوجود منطق أعمال في الواجهة.
   - لا تسمح بوجود اعتماد مباشر من الواجهة على مكتبات فك التشفير.

5. **MVI + UDF**:
   - إدارة الحالة باستخدام **Model-View-Intent** مع **Unidirectional Data Flow**.
   - كل شاشة لها `UiState` واحد غير قابل للتغيير.
   - كل إجراء مستخدم يُمثل بـ `Intent` أو `Event`.
   - استخدام `StateFlow` و`SharedFlow` فقط. لا `LiveData`.

6. **حقن التبعيات باستخدام Hilt**:
   - جميع التبعيات تُحقن عبر Hilt.
   - لا إنشاء يدوي للـ Repositories أو Use Cases داخل ViewModels.

7. **الأداء وإدارة الذاكرة**:
   - لا تسمح بتحميل كل الصفحات في الذاكرة دفعة واحدة.
   - استخدم تخزيناً مؤقتاً محدوداً بالبايت.
   - استخدم التحميل المسبق الذكي وإلغاء التحميل عند الحاجة.
   - تعامل مع ضغط الذاكرة بشكل استباقي.

8. **Scoped Storage**:
   - لا تطلب أذونات تخزين غير ضرورية.
   - اعتمد على `content://` URIs و Storage Access Framework.
   - استخدم `FileProvider` عند مشاركة الملفات.
   - استخدم `cacheDir` و`getExternalFilesDir()` للملفات المؤقتة.

---

## 2. نظرة عامة على التطبيق

**MyLibrary** هو تطبيق أندرويد متعدد الصيغ لقراءة الكتب الرقمية والقصص المصورة. يهدف إلى توفير تجربة قراءة موحدة وسلسة لملفات PDF و EPUB والنصوص العادية وأرشيفات القصص المصورة (CBZ/CBR). يتميز التطبيق بواجهة عربية افتراضية، ودعم كامل للإنجليزية، وتصميم Material 3 متكامل، ومعمارية حديثة قابلة للتوسع.

---

## 3. الصيغ المدعومة

| الصيغة | المكتبة المقترحة 2026 | ملاحظات |
|---|---|---|
| PDF | `pdfmp` أو `KPDF` | عرض عالي الأداء، تكبير/تصغير، تخزين مؤقت، بحث نصي |
| EPUB | `Readium Kotlin Toolkit` | دعم EPUB 2/3، تنقل، بحث، إشارات مرجعية |
| TXT | Built-in | عرض بسيط، دعم RTL/LTR تلقائي، تغيير حجم الخط |
| CBZ | `java.util.zip` + Coil 3 | فك ضغط انتقائي، عرض صور، ترتيب طبيعي |
| CBR | `junrar` + Coil 3 | فك ضغط RAR، نفس خط أنابيب CBZ |

---

## 4. المعمارية المعتمدة

### 4.1 هيكل الوحدات

```text
MyLibrary/
├── app/                          # نقطة الدخول، التنقل، حقن التبعيات
├── core/
│   ├── core-common/              # أدوات مساعدة، ثوابت، امتدادات
│   ├── core-ui/                  # مكونات Compose المشتركة، الثيم، الألوان، الخطوط
│   ├── core-data/                # مستودعات البيانات، DataStore، Room
│   └── core-domain/              # نماذج الأعمال، واجهات المستودعات، حالات الاستخدام
├── feature/
│   ├── feature-library/          # إدارة المكتبة، الإشارات المرجعية، التفضيلات
│   ├── feature-reader/           # عرض الصفحات، التكبير/التصغير، التنقل
│   ├── feature-settings/         # الإعدادات، اللغة، الثيم
│   └── feature-search/           # البحث داخل المستندات
├── format/
│   ├── format-pdf/               # وحدة فك تشفير PDF
│   ├── format-epub/              # وحدة فك تشفير EPUB
│   ├── format-text/              # وحدة عرض النصوص
│   └── format-archive/           # وحدة فك ضغط الأرشيفات
└── build-logic/                  # إضافات Gradle المشتركة