# Чек-лист перед пушем в репозиторий

Этот чек-лист создан после двух неудачных сборок GitHub Actions, чтобы
избежать повторения тех же ошибок. Запускайте его перед `git push`.

## 1. Синтаксис Kotlin raw strings (`"""..."""`)

**Проблема:** В Kotlin raw strings `\$` НЕ работает как escape — `${...}`
всегда интерполируется. Если нужно использовать `${placeholder}` как
литерал в строке-шаблоне, используйте **`{{placeholder}}`** (двойные
фигурные скобки), а не `\${placeholder}`.

```kotlin
// ❌ НЕ РАБОТАЕТ — Kotlin пытается найти переменную contractNumber
const val TEMPLATE = """... № \${contractNumber} ..."""

// ✅ ПРАВИЛЬНО — просто текст, не интерполируется
const val TEMPLATE = """... № {{contractNumber}} ..."""
```

**Проверка:**
```bash
# Не должно быть \${...} в raw strings (const val = """...""")
grep -n '\\\${' app/src/main/java/com/example/**/*.kt
```

## 2. Compose material icons — нужны явные импорты расширений

**Проблема:** `Icons.Default.Delete` — это extension property на `Icons`,
определённое в пакете `androidx.compose.material.icons.filled`. Чтобы
его вызвать, **нужно импортировать сам extension** — квалификация
приёмника `androidx.compose.material.icons.Icons.Default.Delete` НЕ
работает.

```kotlin
// ❌ НЕ РАБОТАЕТ — extension не импортирован
import androidx.compose.material.icons.Icons
Icon(androidx.compose.material.icons.Icons.Default.Delete, ...)

// ✅ ПРАВИЛЬНО — extension импортирован отдельно
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
Icon(Icons.Default.Delete, ...)
```

**Проверка:** перед пушем убеждаемся, что для каждого `Icons.Default.X`
есть соответствующий `import androidx.compose.material.icons.filled.X`.

## 3. `Modifier.weight()` — НЕ импортировать

**Проблема:** `Modifier.weight()` — это extension на `RowScope` /
`ColumnScope`. Он автоматически доступен внутри `Row { }` / `Column { }`.
Если импортировать `androidx.compose.foundation.layout.weight`, компилятор
падает с "Cannot access 'val RowColumnParentData?.weight: Float': it is
internal in file."

```kotlin
// ❌ НЕТ — вызывает internal access error
import androidx.compose.foundation.layout.weight

// ✅ ПРАВИЛЬНО — weight автоматически доступен в Row/Column scope
Row {
    Text("...", modifier = Modifier.weight(1f))
}
```

**Проверка:**
```bash
# Не должно быть такого импорта
grep -n 'import androidx.compose.foundation.layout.weight$' \
    app/src/main/java/com/example/**/*.kt
```

## 4. kotlinx.serialization — нужен импорт reified-расширений

**Проблема:** `Json.encodeToString(value: T)` и
`Json.decodeFromString<T>(string)` — это extension functions на
`StringFormat` (который `Json` реализует). Чтобы их вызвать, нужно
импортировать сами extensions. Без импорта компилятор пытается
сопоставить с member-функцией `encodeToString(serializer, value)` и
падает с "Argument type mismatch: actual type is 'TemplateContent',
but 'SerializationStrategy<uninferred T>' was expected."

```kotlin
// ❌ НЕ РАБОТАЕТ — extension не импортирован, fallback на member
import kotlinx.serialization.json.Json
val json = Json { ... }
json.encodeToString(content)         // ERROR
json.decodeFromString<T>(string)     // ERROR (если не повезло)

// ✅ ПРАВИЛЬНО — оба extension импортированы
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
json.encodeToString(content)         // OK
json.decodeFromString<T>(string)     // OK
```

**Проверка:**
```bash
# Если в файле есть .encodeToString(...) или .decodeFromString<T>(...),
# должны быть импорты:
grep -l 'encodeToString\|decodeFromString' \
    app/src/main/java/com/example/**/*.kt | while read f; do
  grep -q 'import kotlinx.serialization.encodeToString\|import kotlinx.serialization.decodeFromString' "$f" \
    || echo "MISSING IMPORT in $f"
done
```

## 5. ExperimentalMaterial3Api — нужен @OptIn

**Проблема:** Некоторые Compose Material3 API (например `PrimaryTabRow`)
экспериментальные. Без `@OptIn(ExperimentalMaterial3Api::class)` на
функции компилятор выдаёт ошибку "This material API is experimental and
is likely to change".

```kotlin
// ❌ ОШИБКА
@Composable
fun TypeSelector() {
    PrimaryTabRow(...) { ... }
}

// ✅ ПРАВИЛЬНО
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypeSelector() {
    PrimaryTabRow(...) { ... }
}
```

## 6. Перед пушем — проверить локально (если есть окружение)

```bash
./gradlew :app:compileDebugKotlin
```

Если окружения нет (как в этой сессии) — прогнать все проверки выше
визуально и grep'ом.

## История ошибок

| Дата | Коммит | Ошибка | Исправление |
|---|---|---|---|
| 2026-09-24 | 25a3275 | `${placeholder}` в raw string → интерполяция | → `{{placeholder}}` |
| 2026-09-24 | 25a3275 | `androidx.compose.material.icons.Icons.Default.Delete` | + import `filled.Delete` |
| 2026-09-24 | 25a3275 | `import androidx.compose.foundation.layout.weight` | удалить (внутренний доступ) |
| 2026-09-24 | 25a3275 | `PrimaryTabRow` без `@OptIn` | + `@OptIn(ExperimentalMaterial3Api::class)` |
| 2026-09-25 | b439ed8 | `json.encodeToString(content)` без import reified | + `import kotlinx.serialization.encodeToString` |
