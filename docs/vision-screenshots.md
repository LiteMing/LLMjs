# LLMjs Client Screenshot Vision

`LLM.visionActionbar(player, prompt, options)` asks the player's client to capture the current rendered frame, compress it, send it back to the server, call a multimodal provider, and display the final answer in the actionbar.

The captured frame is the current client framebuffer, so it can include HUD, chat, menus, inventories, mod screens, and other rendered UI.

## API

```js
LLM.visionActionbar(player, prompt, {
  provider: 'openai',
  system: 'You are a Minecraft UI assistant.',
  temperature: 0.2,
  maxTokens: 80,

  compression: 'auto',     // auto or manual
  mimeType: 'image/jpeg',  // image/jpeg or image/png
  maxWidth: 768,
  maxBytes: 420000,
  quality: 0.78,
  detail: 'low',

  expected: 'Only show answers that identify a concrete problem.',
  harnessProvider: 'openai',
  harnessPrompt: 'Reply only PASS or FAIL.',
  showHarnessFailures: false
})
```

If `expected` is omitted, the first multimodal answer is shown directly. If `expected` is present, LLMjs runs a second text-only harness request and shows the answer only when the harness returns `PASS`.

## Safety

- Prompt, provider, and harness options are stored server-side in a pending request.
- The client only returns image bytes for the matching request id.
- Images are capped by `max_image_bytes` and `max_image_width` in server config.
- Screenshots are not logged or written to disk by LLMjs.

See `examples/kubejs/server_scripts/llmjs_vision_actionbar.js` for chat-triggered examples.
