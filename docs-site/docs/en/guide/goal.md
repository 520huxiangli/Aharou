# Session goals

Give a session a long-running goal and the AI stops "answer once and quit" — after each turn it continues by itself until the goal is met, you stop it, or the budget runs out.

Use it for work that genuinely takes several turns: finishing a module refactor, clearing every lint warning in a project, or implementing a requirement list item by item.

## Setting a goal

Type this in the input box:

```
/goal Remove every TODO comment in the app module, filling in a note or the implementation for each
```

Press enter and the goal takes effect immediately; the first turn starts right away. A goal bar appears above the input box, showing the goal text, its current state and how many turns it has run.

A goal belongs to **one session**: switch to another session and you get a different goal. The two never affect each other.

To change the goal, just send another `/goal <new description>`. The old goal, including its turn count and usage, is discarded and the new one starts from turn one.

Type `/` and you will see `goal` in the command list with the same description.

## Viewing, pausing and clearing

| Input | Effect |
| --- | --- |
| `/goal` | Show the current goal: state, turns run, text |
| `/goal pause` | Pause: no more automatic continuation |
| `/goal resume` | Resume: start continuing again and run one turn immediately |
| `/goal clear` | Clear the goal (same as `/goal off`) |

The two buttons on the right of the goal bar do the same thing — pause / resume and clear — so you do not have to type the commands.

The result of `/goal`, and the notices for pause and clear, appear in the chat as an assistant message. Those notices are for you only: they are not added to the context and do not affect later replies.

## How the loop behaves

- After every successful turn, as long as the goal is active, Aharou sends a continuation instruction so the AI keeps going;
- **What you say comes first**: messages you send while the AI is running are queued, and your message is handled before the goal continues;
- Tapping the stop button interrupts the current turn and marks the goal as paused — it will not quietly start running again;
- Continuation turns never leave fake user messages in your list; the history only contains the AI's real replies.

## When it stops

The goal bar state tracks the situation:

| State | Meaning |
| --- | --- |
| Active | Continues automatically after each turn |
| Completed | The AI confirmed every requirement in the goal is met and backed by evidence |
| Paused | You paused it, or pressed stop |
| Blocked | Several turns in a row produced no real progress; the AI judged it stuck |
| Budget used up | The turn or token budget ran out |

"Completed" is decided by the AI itself: it checks each requirement in the goal one by one, and only marks it complete when all of them are done with evidence (command output, file changes, and so on). Missing even one keeps it running.

None of the stopped states throw the goal away. Once you have dealt with the problem, send `/goal resume` to continue.

## The two budgets

Under **Settings → General → Preferences → Goal** there are two rows you can change:

| Setting | Default | Meaning |
| --- | --- | --- |
| Max continuation turns | 50 | How many turns one goal may run automatically |
| Token budget | 500000 | Total input plus output tokens the goal loop may spend |

Whichever budget is reached first stops the loop, and the state shows "Budget used up".

The budget is counted from **the moment the goal was set**: pausing and resuming do not reset the turn count or the usage; only setting a new goal with `/goal` starts from zero again.

Running out of budget does not invalidate the goal: raise the numbers, then `/goal resume` to keep going.

## Relation to run modes

- If the session is in PLAN mode when you set a goal, it switches back to BUILD first — an autonomous loop needs to edit files and run commands, and a read-only mode has no way forward;
- Each turn still goes through normal permission control: BUILD mode asks for confirmation as usual, AUTO mode approves as usual. A goal never lets the AI skip an authorization.

## FAQ

**The goal bar says "Blocked" — what now?**
It means several turns in a row did nothing concrete (no file edits, no commands, no real output). Look at where the AI got stuck, supply the missing information or permission, then `/goal resume`.

**The budget runs out far too fast.**
Usually the goal is too broad. Packing five tasks into one goal burns the budget in the first few turns; splitting it into a few smaller goals, each set with its own `/goal`, works out cheaper.

**Will it keep running after I pause?**
No. Pausing keeps the goal text but stops automatic continuation; messages you send yourself still get normal replies.

**Does switching the model clear the goal?**
No. The goal belongs to the session, independent of which provider or model is selected.

**Can I get a cleared goal back?**
No. Clearing removes the goal text, turn count and usage baseline together; set it again with `/goal` if you need it.
