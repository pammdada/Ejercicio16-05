# Plan de Implementación: Verificación IMAP para Agentes KoogIA

## Objetivo
Integrar un segundo agente de verificación IMAP sin modificar la lógica ni el flujo del agente actual de extracción de vouchers.

## Cambios en Archivos Existentes

### 1. VoucherResult.kt
- **Cambio**: Agregar campo `entidad: String? = null` a `VoucherData`.
- **Justificación**: El agente actual (Agente 1) extraerá del voucher la entidad emisora (BCP, BBVA, YAPE, etc.).

### 2. Routing.kt
- **Cambio 1**: Actualizar el `systemPrompt` del nodo `extractNode` para instruir al LLM a extraer el campo `"entidad"` y agregarlo al JSON de salida.
- **Cambio 2**: Agregar un nuevo endpoint `POST /ai/verify-payment` al final del bloque `route("/ai")`.
- **Restricción**: No se elimina ningún comentario ni se modifica la lógica del grafo existente (classifyNode, extractNode, edges, condiciones).

### 3. build.gradle.kts
- **Cambio**: Agregar dependencia `implementation("org.eclipse.angus:angus-mail:2.0.3")`.

### 4. application.yaml
- **Cambio**: Agregar bloque de configuración IMAP con valores directos (sin variables de entorno):
  ```yaml
  imap:
    host: imap.gmail.com
    port: 993
    username: tu_correo@gmail.com
    password: tu_app_password
    ssl: true
    searchDaysBack: 1
    searchDaysForward: 1
  ```

## Nuevos Archivos

### 5. ImapConfig.kt
Data class para encapsular la configuración IMAP leída de `application.yaml`.

### 6. ImapMailService.kt
Servicio puro de Jakarta Mail (`org.eclipse.angus`).
- Conexión/reutilización de `Store`.
- Búsqueda por rango de fechas (`ReceivedDateTerm`).
- Extracción de contenido de correos (text/plain y multipart).
- Búsqueda del número de operación en el cuerpo del correo.
- Ejecución en `Dispatchers.IO`.
- Singleton gestionado por Ktor.

### 7. ImapVerificationToolSet.kt
Implementa `ToolSet` de Koog.
- Expone la función `@Tool verifyPaymentInEmail(date, operationNumber)`.
- Devuelve `"true"` o `"false"` como string para que el LLM lo interprete.

### 8. PaymentVerificationRequest.kt
DTO de entrada para el endpoint `/verify-payment`:
```kotlin
data class PaymentVerificationRequest(
    val fecha: String,
    val numeroTransaccion: String
)
```

### 9. PaymentVerificationResult.kt
DTO de salida del endpoint `/verify-payment`:
```kotlin
data class PaymentVerificationResult(
    val verified: Boolean,
    val message: String? = null
)
```

## Flujo del Nuevo Agente (Agente 2)
1. Cliente llama `POST /ai/verify-payment` con `PaymentVerificationRequest`.
2. Se instancia un `AIAgent` con `systemPrompt` y `ToolRegistry` que contiene `ImapVerificationToolSet`.
3. El LLM invoca la tool `verifyPaymentInEmail` con los parámetros recibidos.
4. La tool ejecuta `ImapMailService.verifyOperationExists(...)` que busca en IMAP.
5. La tool devuelve `"true"` o `"false"`.
6. El agente devuelve ese valor al endpoint.
7. El endpoint parsea y responde con `PaymentVerificationResult`.

## Reglas de Implementación
- **Sin variables de entorno**: Todo en `application.yaml` con valores directos.
- **Sin tocar agente actual**: Solo se agrega el campo `entidad` y se actualiza el prompt JSON. El grafo, nodos, edges y comentarios existentes se preservan al 100%.
- **Jakarta Mail**: Usar `org.eclipse.angus:angus-mail`.
- **Soporte genérico IMAP**: Funciona con Gmail, Outlook u cualquier servidor IMAP estándar.

## Aprobación
Este plan está listo para ser ejecutado. Se solicita aprobación para proceder con los cambios en el sistema de archivos.
