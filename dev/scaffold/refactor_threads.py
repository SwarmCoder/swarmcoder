import os
import re

def process_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
    
    original = text
    
    # 1. Replace getElement().addEventListener( -> addDomEventListener(
    text = text.replace('.getElement().addEventListener(', '.addDomEventListener(')
    
    # 2. We need to find places where 'e -> new Thread(() -> {' or 'e -> new Thread(() -> ...)' is used
    #    and remove the 'new Thread(() -> ' and the trailing ').start()'
    # Because writing a full bracket parser in a quick script is annoying, let's use a regex that matches the most common single-line forms first:
    # e -> new Thread(() -> chat.setChatModel(chatId, value)).start()
    text = re.sub(r'e -> new Thread\(\(\) -> (.*?)\)\.start\(\)', r'e -> \1', text)
    text = re.sub(r'\(EventListener<Event>\) e -> new Thread\(\(\) -> (.*?)\)\.start\(\)', r'(EventListener<Event>) e -> \1', text)
    
    # Now for multi-line: e -> new Thread(() -> { ... }).start()
    # We can use a bracket matching function to do this safely.
    
    while True:
        # Find 'e -> new Thread(() -> {' or similar
        match = re.search(r'(e -> |\w+\s+e\s+->\s*)new Thread\(\(\) ->\s*\{', text)
        if not match:
            # Let's also search for places without '{' just in case there's a multiline single statement
            match2 = re.search(r'(e -> |\w+\s+e\s+->\s*)new Thread\(\(\) ->\s*([^{]+?)\)\.start\(\)', text)
            if match2:
                text = text[:match2.start()] + match2.group(1) + match2.group(2) + text[match2.end():]
                continue
            break
            
        start_idx = match.start()
        end_of_match = match.end() - 1 # points to '{'
        
        # find matching '}'
        count = 0
        end_brace = -1
        for i in range(end_of_match, len(text)):
            if text[i] == '{':
                count += 1
            elif text[i] == '}':
                count -= 1
                if count == 0:
                    end_brace = i
                    break
                    
        if end_brace != -1:
            # Look for ').start()' after the brace
            suffix = text[end_brace+1:end_brace+20]
            if ').start()' in suffix:
                start_offset = suffix.find(').start()') + len(').start()')
                
                # Replace it!
                prefix = text[:start_idx] + match.group(1) + '{'
                body = text[end_of_match+1:end_brace]
                suffix_part = text[end_brace+1 + start_offset:]
                
                text = prefix + body + '}' + suffix_part
                continue
            else:
                print(f"Warning: could not find .start() after closing brace at {path}")
                break
        else:
            print(f"Warning: could not find matching brace at {path}")
            break
            
    # Finally, let's also catch cases like new Button("Approve", e -> new Thread(() -> { ... }).start())
    # The regex above `(e -> |...)` should catch it, but let's be sure.
            
    if text != original:
        print(f"Updated {path}")
        with open(path, 'w', encoding='utf-8') as f:
            f.write(text)

d = 'C:/work/swarmcoder/sc-console-ui/src/main/java/com/swarmcoder/console/ui'
for root, dirs, files in os.walk(d):
    for f in files:
        if f.endswith('.java'):
            process_file(os.path.join(root, f))
