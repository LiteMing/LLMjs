# LLMjs Vision Primitives

LLMjs exposes small vision primitives that scripts can compose into their own workflows. It does not require actionbar output, harness checks, or a specific prompt pattern.

## Capture Current Client Frame

```js
LLM.captureScreenshot(player, {
  compression: 'auto',     // auto or manual
  mimeType: 'image/jpeg',  // image/jpeg or image/png
  maxWidth: 768,
  maxBytes: 420000,
  quality: 0.78,
  detail: 'low'
}, image => {
  // image is a VisionImage
}, error => {
  // optional capture error callback
})
```

The capture is the current client framebuffer, so it can include HUD, chat, menus, inventories, mod screens, and other rendered UI.

## Read Held Exposure Photo

```js
const image = LLM.getExposurePhoto(player, {
  maxBytes: 420000,
  detail: 'low'
})
```

This is server-side soft compatibility. LLMjs does not depend on Exposure classes. It checks for `exposure:photograph` or `exposure:aged_photograph`, extracts the exposure id from item NBT, then reads `data/exposures/<id>.dat` or the server's in-memory saved-data cache.

## Send Image To A Provider

```js
LLM.chatImage('Describe this image briefly.', image, {
  provider: 'openai',
  system: 'Be concise.',
  maxTokens: 80
}, result => {
  if (result.success) LLM.actionbar(player, result.content)
})
```

`VisionImage` works the same whether it came from `captureScreenshot` or `getExposurePhoto`.

For a harness, run a normal second `LLM.chat(...)` call over the candidate response and decide in script whether to show it.

See `examples/kubejs/server_scripts/llmjs_vision_primitives.js`.
