use serde::Serialize;

/// Generic tree data structure, reusable across all commands.
#[derive(Serialize)]
pub struct Tree<T> {
    pub value: T,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub children: Vec<Tree<T>>,
}

impl<T> Tree<T> {
    /// Apply a function to each node in pre-order.
    pub fn preorder(&self) -> TreeIter<'_, T> {
        TreeIter { stack: vec![self] }
    }

    /// Attach a child node.
    pub fn child(mut self, child: Tree<T>) -> Self {
        self.children.push(child);
        self
    }
}

pub struct TreeIter<'a, T> {
    stack: Vec<&'a Tree<T>>,
}

impl<'a, T> Iterator for TreeIter<'a, T> {
    type Item = &'a T;

    fn next(&mut self) -> Option<Self::Item> {
        let node = self.stack.pop()?;
        for child in node.children.iter().rev() {
            self.stack.push(child);
        }
        Some(&node.value)
    }
}
