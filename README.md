# demoMAGIC - Nébula Sur (Front + Back)

Demo empresarial completa con:

- Landing profesional de **Nébula Sur**
- Chat IA con selector de base de conocimiento **KB A / KB B**
- Carrito temporal en memoria (sin persistencia)
- Solicitud de propuesta por WhatsApp y Email
- Contacto directo (WhatsApp / Email / Teléfono)
- Multiidioma **ES / EN**
- Arquitectura lista para desplegar: **Front en Netlify** y **Back en Railway**

## Estructura

- `front/` HTML + CSS + JS puro
- `back/` Java Spring Boot (Maven)
- `netlify.toml` configuración de publicación estática

## Funcionalidades implementadas

1. Chat IA empresarial con estado `Escribiendo...`
2. Selector de base KB (`A` / `B`)
3. Detección de acciones en backend:
- `ADD`
- `REMOVE`
- `CLEAR`
- `SHOW`
4. Carrito responsive:
- Móvil: drawer inferior + botón flotante
- Tablet: panel lateral
- PC: sidebar fija
5. Share de demo con QR de la URL actual
6. Sección “Qué hacemos” con cards
7. WhatsApp / Email / Llamada con datos de contacto configurados
8. RAG simple en backend:
- Carga de `kbA.txt` y `kbB.txt`
- Chunk por artículo
- Embeddings en memoria (si hay API key)
- Búsqueda por similitud
- Respuesta con citas

## Variables de entorno

Se usa `.env` local (ignorado por Git). Variables clave:

- `OPENAI_API_KEY`
- `ALLOWED_ORIGINS`
- `PORT`
- `OPENAI_CHAT_MODEL`
- `OPENAI_EMBEDDING_MODEL`

> Importante: no hardcodear la API key en código.

## Ejecutar en local

## 1) Backend (Spring Boot)

```powershell
cd back
mvn spring-boot:run
```

Backend disponible en:

- `http://localhost:8080/health`
- `http://localhost:8080/api/chat`

## 2) Frontend (estático)

Desde raíz del repo:

```powershell
cd front
python -m http.server 5500
```

Abrir:

- `http://localhost:5500`

El front usa `front/config.js`:

```js
window.APP_CONFIG = {
  API_BASE_URL: "http://localhost:8080"
};
```

## Deploy en Netlify (Front)

Archivo ya creado en raíz:

```toml
[build]
publish = "front"
command = ""
```

Pasos:

1. Crear nuevo sitio en Netlify desde el repo.
2. Verificar que el publish directory sea `front`.
3. Deploy.

## Deploy en Railway (Back)

El backend está preparado con puerto dinámico:

```properties
server.port=${PORT:8080}
```

Pasos:

1. Crear proyecto en Railway conectado al repo.
2. Configurar el servicio apuntando a carpeta `back`.
3. Definir variables en Railway:
- `OPENAI_API_KEY`
- `ALLOWED_ORIGINS` (incluye dominio Netlify)
4. Deploy.

## Endpoint de chat

`POST /api/chat`

Request:

```json
{
  "kb": "A",
  "message": "Añade auditoría de seguridad",
  "cart": [],
  "lang": "es"
}
```

Response:

```json
{
  "reply": "...",
  "actions": [{ "type": "ADD", "itemId": "A-10" }],
  "item": {
    "id": "A-10",
    "title": "Auditoría de seguridad y cumplimiento en tienda online"
  },
  "citations": ["A-10 - Auditoría de seguridad y cumplimiento en tienda online"]
}
```

## Ejemplos de preguntas para la demo

- "Compárame dos opciones para mejorar conversión en eCommerce"
- "Añade el recomendador inteligente al carrito"
- "Quita la auditoría de seguridad"
- "Muéstrame el carrito y el total estimado"
- "Vacía el carrito"
- "Switch to KB B and recommend something for logistics"
- "I need a proposal summary by email"

## Notas

- No hay login ni persistencia de datos.
- El carrito se mantiene en memoria del frontend.
- Los precios son orientativos y pueden variar según alcance.
