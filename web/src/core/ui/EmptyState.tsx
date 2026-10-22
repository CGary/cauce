export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="flex flex-col items-center gap-1 rounded-xl border border-dashed border-stone-300 px-4 py-10 text-center">
      <p className="font-medium">{title}</p>
      {description && <p className="text-sm text-stone-600">{description}</p>}
    </div>
  )
}
