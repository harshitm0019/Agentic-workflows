'use client';

type DiffViewerProps = {
  diff: string;
};

export function DiffViewer({ diff }: DiffViewerProps) {
  const lines = diff.split('\n');

  let oldLineNum = 0;
  let newLineNum = 0;

  const parsedLines = lines.map((line) => {
    let type: 'add' | 'remove' | 'hunk' | 'header' | 'context' = 'context';
    let oldNum: number | null = null;
    let newNum: number | null = null;

    if (line.startsWith('@@')) {
      type = 'hunk';
      // Parse hunk header: @@ -oldStart,count +newStart,count @@
      const match = line.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
      if (match) {
        oldLineNum = parseInt(match[1], 10) - 1;
        newLineNum = parseInt(match[2], 10) - 1;
      }
    } else if (
      line.startsWith('diff ') ||
      line.startsWith('index ') ||
      line.startsWith('---') ||
      line.startsWith('+++')
    ) {
      type = 'header';
    } else if (line.startsWith('+')) {
      type = 'add';
      newLineNum++;
      newNum = newLineNum;
    } else if (line.startsWith('-')) {
      type = 'remove';
      oldLineNum++;
      oldNum = oldLineNum;
    } else {
      oldLineNum++;
      newLineNum++;
      oldNum = oldLineNum;
      newNum = newLineNum;
    }

    return { line, type, oldNum, newNum };
  });

  return (
    <div className="mt-3 border border-gray-200 rounded-lg overflow-hidden">
      <div className="overflow-auto max-h-96">
        <table className="w-full text-xs font-mono border-collapse">
          <tbody>
            {parsedLines.map((parsed, i) => {
              let rowClass = '';
              let lineClass = 'text-gray-700';
              let gutterClass = 'text-gray-400 bg-gray-50';

              if (parsed.type === 'add') {
                rowClass = 'bg-green-50';
                lineClass = 'text-green-900';
                gutterClass = 'text-green-600 bg-green-100';
              } else if (parsed.type === 'remove') {
                rowClass = 'bg-red-50';
                lineClass = 'text-red-900';
                gutterClass = 'text-red-600 bg-red-100';
              } else if (parsed.type === 'hunk') {
                rowClass = 'bg-blue-50';
                lineClass = 'text-blue-700 font-medium';
                gutterClass = 'text-blue-400 bg-blue-50';
              } else if (parsed.type === 'header') {
                rowClass = 'bg-gray-100';
                lineClass = 'text-gray-600 font-semibold';
                gutterClass = 'bg-gray-100';
              }

              return (
                <tr key={i} className={rowClass}>
                  <td
                    className={`${gutterClass} select-none text-right px-2 py-0 w-10 border-r border-gray-200 align-top`}
                  >
                    {parsed.oldNum ?? ''}
                  </td>
                  <td
                    className={`${gutterClass} select-none text-right px-2 py-0 w-10 border-r border-gray-200 align-top`}
                  >
                    {parsed.newNum ?? ''}
                  </td>
                  <td className={`${lineClass} px-3 py-0 whitespace-pre`}>
                    {parsed.line}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
