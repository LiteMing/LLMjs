// LLMjs vision examples.
// Copy into kubejs/server_scripts/ and reload scripts.
//
// Commands shown here use chat triggers for readability:
//   !ui        automatic screenshot compression, show answer in actionbar
//   !uimanual  manual compression settings
//   !uiharness only show the answer when a harness says it matches expectation
//
// The screenshot is captured on the client after the server sends a request.
// It includes the current rendered client frame, including HUD and open screens.

PlayerEvents.chat(event => {
  const msg = event.message.trim()
  const player = event.player

  if (msg === '!ui') {
    event.cancel()
    LLM.visionActionbar(player, [
      'Inspect the current Minecraft screenshot, including any open UI.',
      'Return one concise actionable hint for the player.',
      'If nothing important is visible, say "No urgent UI issue."'
    ].join('\n'), {
      provider: 'openai',
      compression: 'auto',
      maxWidth: 768,
      maxBytes: 420000,
      quality: 0.78,
      detail: 'low',
      maxTokens: 80,
      system: 'You are a Minecraft UI assistant. Be precise and brief.'
    })
  }

  if (msg === '!uimanual') {
    event.cancel()
    LLM.visionActionbar(player, 'Read the current screen and summarize the selected UI element in under 50 characters.', {
      provider: 'openai',
      compression: 'manual',
      mimeType: 'image/jpeg',
      maxWidth: 512,
      maxBytes: 250000,
      quality: 0.62,
      detail: 'low',
      maxTokens: 60
    })
  }

  if (msg === '!uiharness') {
    event.cancel()
    LLM.visionActionbar(player, [
      'Look for a clear warning, error, danger, blocked button, missing item,',
      'or other actionable problem in the current Minecraft UI/screen.',
      'If present, answer with the problem only. If absent, answer "OK".'
    ].join('\n'), {
      provider: 'openai',
      compression: 'auto',
      maxWidth: 900,
      maxBytes: 500000,
      quality: 0.75,
      detail: 'low',
      maxTokens: 80,

      // Harness pass condition. The actionbar is shown only if the candidate
      // answer satisfies this condition.
      expected: 'The answer identifies a concrete problem, warning, danger, blocked UI state, or missing requirement. It should not be just OK.',
      harnessProvider: 'openai',
      harnessPrompt: 'Reply only PASS or FAIL. PASS if the candidate answer should be shown to the player as an actionable UI warning.',
      showHarnessFailures: false
    })
  }
})
