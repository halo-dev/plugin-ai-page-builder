# AI Page Builder

Content and progress concepts used during page generation, distinguishing staged edits,
recoverable progress, and saved drafts.

## Language

**Working Copy**:
The mutable page content and proposed path used during one page-generation run.
Its edits become the page's draft only when the run commits; saving a checkpoint does not
update the draft.
_Avoid_: Saved draft (when referring to an uncommitted working copy)

**Checkpoint**:
Persisted progress used to explicitly resume an interrupted run, including its working copy
and the context needed to continue generation.

**Draft**:
The page's currently saved, editable content version, independent of the published version
shown to visitors.

**Conversation Turn**:
A user request together with the assistant's responses, tool activity, clarification questions,
and follow-up instructions during the resulting page-generation run.

**Import Review**:
A time-limited assessment of a page archive, tied to the archive's exact contents and the
reviewing user. Its verdict determines whether import is allowed, requires risk confirmation,
or is blocked.
